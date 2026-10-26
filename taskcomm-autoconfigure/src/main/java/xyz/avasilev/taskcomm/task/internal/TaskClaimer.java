package xyz.avasilev.taskcomm.task.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Захват работы по аренде (docs/adr/0006-lease-based-claiming.md, docs/adr/0046-lease-in-attempt.md,
 * docs/adr/0067-expired-lease-closes-attempt.md). Один такт — одна короткая транзакция, процессор
 * работает уже после её коммита.
 *
 * <p>Запрос такта — две ветки через {@code UNION ALL}, а не один {@code OR}: у них разные точки
 * входа.
 *
 * <ul>
 *   <li><b>Первая выдаёт работу</b>: задачи, группы и подзадачи в {@code PENDING}, готовые
 *       по {@code next_attempt_at}, без открытой попытки. Каждый тип поллера спрашивается своей
 *       пробой по частичному индексу формы захвата с типом на втором месте, и блокирует тип только
 *       свою долю (docs/adr/0129-claim-by-type-share-locking.md): сначала головы типов читаются
 *       без блокировок и сливаются в порядке захвата, затем каждый тип со {@code SKIP LOCKED}
 *       блокирует столько строк, сколько у него в слитой голове. Чужие и осиротевшие типы
 *       не читаются. Сущность становится {@code RUNNING}, для неё <b>вставляется</b> строка
 *       попытки с новым токеном и {@code locked_until = now() + аренда}. Двух открытых попыток
 *       у сущности не даст уникальный частичный индекс.
 *   <li><b>Вторая работу не выдаёт</b>: открытые попытки с истёкшей арендой закрываются исходом
 *       {@code LEASE_LOST}, сущность возвращается в очередь с backoff или становится
 *       {@code FAILED}. Брошенная работа вернётся через первую ветку, когда наступит её
 *       {@code next_attempt_at}.
 * </ul>
 *
 * <p>Обе ветки содержат условие {@code execution_created_at >= active_from}, а транзакция ставит
 * {@code force_custom_plan}: граница известна планировщику, и партиции ниже неё не открываются
 * и не блокируются (docs/adr/0101-active-partition-horizon.md). Общего родителя запрос
 * не блокирует — только свои строки.
 */
public class TaskClaimer {

    private final TaskClaimQuery query = new TaskClaimQuery();

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final DoubleSupplier random;

    private final TaskAttemptOpener opener;

    private final TaskClaimGuard guard;

    /**
     * Собирает захват.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param settings настройки попыток по типу и фазе
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     * @param random источник случайных чисел: джиттер backoff и жребий формы {@code quota}
     * @param instance имя этого экземпляра — пишется в {@code locked_by}
     */
    TaskClaimer(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final AttemptSettingsSource settings,
            final PollerLayout pollers,
            final DoubleSupplier random,
            final String instance) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = TaskSql.transactions(transactionManager);
        this.random = random;
        this.opener = new TaskAttemptOpener(ids, settings, instance);
        this.guard = new TaskClaimGuard(dataSource, transactionManager, settings, pollers, random);
    }

    /**
     * Один такт захвата поллера: закрывает до {@code batchSize} брошенных попыток и захватывает
     * до {@code limit} готовых сущностей.
     *
     * @param poller поллер, от имени которого идёт захват
     * @param types типы, которые поллер вправе захватывать, с формой контракта их процессоров;
     *     задача, начатая по другой форме, отменяется (docs/adr/0026-contract-shape-marking.md)
     * @param limit сколько захватить, не больше свободных воркеров
     * @return захваченная работа, в порядке формы захвата
     */
    List<ClaimedAttempt> claim(final PollerSettings poller, final Map<String, ContractShape> types, final int limit) {
        final Ordering ordering = this.ordering(poller);
        final Tick tick = this.transactions.execute(status -> this.tick(ordering, types, limit, poller.batchSize()));
        if (tick == null) {
            return List.of();
        }
        tick.afterCommit().run();
        return tick.claimed();
    }

    /** Тело транзакции такта: брошенные попытки, проверка кандидатов, открытие попыток пачкой. */
    private Tick tick(
            final Ordering ordering, final Map<String, ContractShape> types, final int limit, final int expiredLimit) {
        TaskSql.forceCustomPlan(this.jdbc);
        final TaskPartitionHorizon.ActiveRange range = TaskPartitionHorizon.activeRange(this.jdbc);
        final Instant activeFrom = range.from();
        final List<Row> rows = this.readBranches(range, ordering, types, limit, expiredLimit);
        final List<Candidate> candidates = rows.stream()
                .filter(Candidate.class::isInstance)
                .map(Candidate.class::cast)
                .toList();
        final Set<TaskEntityRef> stopped = new HashSet<>();
        final Runnable afterCommit = this.guard.check(
                this.jdbc,
                activeFrom,
                rows.stream()
                        .filter(Expired.class::isInstance)
                        .map(row -> ((Expired) row).attempt())
                        .toList(),
                candidates.stream()
                        .map(candidate -> new TaskClaimGuard.ClaimCandidate(
                                candidate.entity(),
                                candidate.taskType(),
                                candidate.shape(),
                                candidate.snapshot().parent()))
                        .toList(),
                types,
                stopped);
        final List<ClaimedAttempt> claimed = this.opener.open(
                this.jdbc,
                activeFrom,
                candidates.stream()
                        .filter(candidate -> !stopped.contains(candidate.entity()))
                        .sorted(ordering.candidates())
                        .limit(limit)
                        .map(candidate -> TaskAttemptOpener.opening(
                                candidate.entity(),
                                candidate.taskType(),
                                candidate.phase(),
                                candidate.shape(),
                                types.get(candidate.taskType()),
                                candidate.snapshot()))
                        .toList());
        return new Tick(claimed, afterCommit);
    }

    private List<Row> readBranches(
            final TaskPartitionHorizon.ActiveRange range,
            final Ordering ordering,
            final Map<String, ContractShape> types,
            final int limit,
            final int expiredLimit) {
        final List<String> polled = types.keySet().stream().sorted().toList();
        return TaskClaimQuery.bind(this.jdbc.sql(this.query.text(ordering, polled)), range, polled, limit, expiredLimit)
                .query(TaskClaimer::row)
                .list();
    }

    private Ordering ordering(final PollerSettings poller) {
        return switch (poller.form()) {
            case DEADLINE -> Ordering.DEADLINE;
            case PRIORITY -> Ordering.PRIORITY;
            case QUOTA -> this.random.getAsDouble() < poller.fifoShare() ? Ordering.FIFO : Ordering.PRIORITY;
        };
    }

    private static Row row(final ResultSet rs, final int rowNum) throws SQLException {
        final TaskEntityRef entity = new TaskEntityRef(
                TaskLevel.valueOf(rs.getString("level")),
                rs.getObject("target_id", UUID.class),
                ClaimRows.instant(rs, "execution_created_at"));
        final String phase = rs.getString("phase");
        final TaskPhase taskPhase = phase == null ? null : TaskPhase.valueOf(phase);
        final UUID attemptId = rs.getObject("attempt_id", UUID.class);
        if (attemptId != null) {
            return new Expired(new ExpiredAttempt(
                    entity,
                    rs.getString("task_type"),
                    taskPhase,
                    attemptId,
                    rs.getString("locked_by"),
                    rs.getInt("lease_renewals"),
                    ClaimRows.parent(rs)));
        }
        final String shape = rs.getString("contract_shape");
        return new Candidate(
                entity,
                rs.getString("task_type"),
                taskPhase,
                shape == null ? null : ContractShape.valueOf(shape),
                new OrderKey(
                        rs.getInt("priority"),
                        ClaimRows.instant(rs, "next_attempt_at"),
                        ClaimRows.instant(rs, "claim_order_at")),
                new ClaimedEntity(
                        rs.getObject("execution_id", UUID.class),
                        rs.getInt("priority"),
                        ClaimRows.instant(rs, "created_at"),
                        ClaimRows.origin(rs),
                        ClaimRows.parent(rs),
                        rs.getString("payload")));
    }

    /** Порядок, в котором выбирается готовая работа в этом такте. */
    enum Ordering {
        DEADLINE("%1$s.claim_order_at"),
        PRIORITY("%1$s.priority, %1$s.next_attempt_at"),
        FIFO("%1$s.next_attempt_at");

        /** Колонки {@code ORDER BY}; на месте {@code %1$s} — псевдоним выборки. */
        private final String columns;

        Ordering(final String columns) {
            this.columns = columns;
        }

        /** {@code ORDER BY} этой формы по колонкам выборки {@code alias}. */
        String orderBy(final String alias) {
            return " ORDER BY " + this.columns.formatted(alias);
        }

        /** Порядок кандидатов такта — по их ключам. */
        private Comparator<Candidate> candidates() {
            return Comparator.comparing(Candidate::key, this.comparator());
        }

        /** Тот же порядок для слияния кандидатов трёх уровней. */
        private Comparator<OrderKey> comparator() {
            return switch (this) {
                case DEADLINE -> Comparator.comparing(OrderKey::claimOrderAt);
                case PRIORITY -> Comparator.comparingInt(OrderKey::priority).thenComparing(OrderKey::nextAttemptAt);
                case FIFO -> Comparator.comparing(OrderKey::nextAttemptAt);
            };
        }
    }

    /** Строка запроса такта: кандидат первой ветки или брошенная попытка второй. */
    private sealed interface Row permits Candidate, Expired {}

    /**
     * Кандидат первой ветки — готовая сущность без открытой попытки.
     *
     * @param entity ключ строки сущности
     * @param taskType тип задачи
     * @param phase фаза сущности; у задачи до первого захвата пусто
     * @param shape форма контракта задачи; пусто до первого захвата и у детей
     * @param key ключи порядка захвата
     * @param snapshot снимок сущности для воркера
     */
    private record Candidate(
            TaskEntityRef entity,
            String taskType,
            TaskPhase phase,
            ContractShape shape,
            OrderKey key,
            ClaimedEntity snapshot)
            implements Row {}

    /**
     * Ключи порядка захвата кандидата.
     *
     * @param priority приоритет
     * @param nextAttemptAt готовность
     * @param claimOrderAt ключ порядка формы {@code deadline}
     */
    private record OrderKey(int priority, Instant nextAttemptAt, Instant claimOrderAt) {}

    /**
     * Брошенная попытка второй ветки — открытая, с истёкшей арендой.
     *
     * @param attempt брошенная попытка
     */
    private record Expired(ExpiredAttempt attempt) implements Row {}

    /**
     * Итог такта: захваченная работа и закрытые им брошенные попытки.
     *
     * @param claimed захваченная работа
     * @param afterCommit каскад после коммита: брошенные попытки и закрытые кандидаты
     */
    private record Tick(List<ClaimedAttempt> claimed, Runnable afterCommit) {}
}
