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
 *       по {@code next_attempt_at}, без открытой попытки; ведёт частичный индекс формы захвата.
 *       Сущность становится {@code RUNNING}, для неё <b>вставляется</b> строка попытки с новым
 *       токеном и {@code locked_until = now() + аренда}. Двух открытых попыток у сущности не даст
 *       уникальный частичный индекс.
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

    private static final String EXPIRED = """
            expired AS (SELECT a.level, a.execution_created_at, a.target_id, NULL::uuid AS execution_id,
                    coalesce(t.task_type, b.task_type, u.task_type) AS task_type, a.phase, NULL::text AS contract_shape,
                    NULL::smallint AS priority, NULL::timestamptz AS next_attempt_at,
                    NULL::timestamptz AS claim_order_at, NULL::text AS payload, NULL::timestamptz AS created_at,
                    NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id,
                    NULL::text AS origin_relation,
                    coalesce(b.task_id, u.task_id) AS parent_task_id, u.batch_id AS parent_batch_id,
                    coalesce(b.parent_attempt_id, u.parent_attempt_id) AS parent_attempt_id,
                    a.id AS attempt_id, a.locked_by, a.lease_renewals
               FROM tc_task_attempt a
               LEFT JOIN tc_task t ON a.level = 'TASK' AND t.execution_created_at >= :activeFrom
                    AND t.execution_created_at = a.execution_created_at AND t.id = a.target_id
               LEFT JOIN tc_task_batch b ON a.level = 'BATCH' AND b.execution_created_at >= :activeFrom
                    AND b.execution_created_at = a.execution_created_at AND b.id = a.target_id
               LEFT JOIN tc_task_unit u ON a.level = 'UNIT' AND u.execution_created_at >= :activeFrom
                    AND u.execution_created_at = a.execution_created_at AND u.id = a.target_id
              WHERE a.finished_at IS NULL
                AND a.locked_until < now()
                AND a.execution_created_at >= :activeFrom
              ORDER BY a.locked_until
              LIMIT :expiredLimit
                FOR UPDATE OF a SKIP LOCKED)
            """;

    /**
     * Сами ветки — выборки из CTE: блокировать строки ({@code FOR UPDATE}) ветка {@code UNION ALL}
     * в PostgreSQL не может, а CTE может.
     */
    private static final String BRANCHES = """

            SELECT * FROM tc_task_candidates
            UNION ALL SELECT * FROM tc_task_batch_candidates
            UNION ALL SELECT * FROM tc_task_unit_candidates
            UNION ALL SELECT * FROM expired
            """;

    /** Указатель на создателя есть только у задачи (docs/adr/0064-entity-origin-links.md). */
    private static final String TASK_ORIGIN = "e.origin_type, e.origin_id, e.origin_attempt_id, e.origin_relation";

    private static final String NO_ORIGIN =
            "NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id, "
                    + "NULL::text AS origin_relation";

    /** Родство детей раскрытия (docs/adr/0047-children-belong-to-attempt.md); у задачи его нет. */
    private static final String NO_PARENT =
            "NULL::uuid AS parent_task_id, NULL::uuid AS parent_batch_id, NULL::uuid AS parent_attempt_id";

    private static final String BATCH_PARENT =
            "e.task_id AS parent_task_id, NULL::uuid AS parent_batch_id, e.parent_attempt_id";

    private static final String UNIT_PARENT =
            "e.task_id AS parent_task_id, e.batch_id AS parent_batch_id, e.parent_attempt_id";

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

    /** Тело транзакции такта: брошенные попытки, проверка кандидатов, открытие попыток. */
    private Tick tick(
            final Ordering ordering, final Map<String, ContractShape> types, final int limit, final int expiredLimit) {
        TaskSql.forceCustomPlan(this.jdbc);
        final Instant activeFrom = TaskPartitionHorizon.activeFrom(this.jdbc);
        final List<Row> rows = this.readBranches(activeFrom, ordering, types, limit, expiredLimit);
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
        final List<ClaimedAttempt> claimed = candidates.stream()
                .filter(candidate -> !stopped.contains(candidate.entity()))
                .sorted(ordering.candidates())
                .limit(limit)
                .map(candidate -> this.start(candidate, types.get(candidate.taskType())))
                .toList();
        return new Tick(claimed, afterCommit);
    }

    private List<Row> readBranches(
            final Instant activeFrom,
            final Ordering ordering,
            final Map<String, ContractShape> types,
            final int limit,
            final int expiredLimit) {
        return this.jdbc
                .sql(branchesQuery(ordering))
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("types", types.keySet().toArray(String[]::new))
                .param("limit", limit)
                .param("expiredLimit", expiredLimit)
                .query(TaskClaimer::row)
                .list();
    }

    /**
     * Запрос такта целиком — обе ветки. Параметры: {@code activeFrom}, {@code types}, {@code limit},
     * {@code expiredLimit}. Открыт пакету ради прототипа отсечения партиций, который проверяет
     * планы и блокировки ровно этого текста.
     *
     * @param ordering порядок первой ветки
     * @return текст запроса
     */
    static String branchesQuery(final Ordering ordering) {
        return "WITH "
                + String.join(
                        ",\n",
                        candidates(TaskLevel.TASK, "e.phase", "e.contract_shape", ordering),
                        candidates(TaskLevel.BATCH, "e.phase", "NULL::text", ordering),
                        candidates(TaskLevel.UNIT, "'PROCESS'", "NULL::text", ordering),
                        EXPIRED)
                + BRANCHES;
    }

    private Ordering ordering(final PollerSettings poller) {
        return switch (poller.form()) {
            case DEADLINE -> Ordering.DEADLINE;
            case PRIORITY -> Ordering.PRIORITY;
            case QUOTA -> this.random.getAsDouble() < poller.fifoShare() ? Ordering.FIFO : Ordering.PRIORITY;
        };
    }

    private ClaimedAttempt start(final Candidate candidate, final ContractShape shape) {
        final TaskPhase phase = candidate.phase() == null ? shape.firstPhase() : candidate.phase();
        final boolean firstClaim = candidate.entity().level() == TaskLevel.TASK && candidate.shape() == null;
        return this.opener.open(
                this.jdbc, candidate.entity(), candidate.taskType(), phase, firstClaim, shape, candidate.snapshot());
    }

    private static String candidates(
            final TaskLevel level, final String phase, final String contractShape, final Ordering ordering) {
        return """
                %6$s AS (SELECT '%1$s' AS level, e.execution_created_at, e.id AS target_id, e.execution_id,
                        e.task_type, %2$s AS phase, %3$s AS contract_shape, e.priority, e.next_attempt_at,
                        e.claim_order_at, e.payload::text AS payload, e.created_at, %7$s, %8$s,
                        NULL::uuid AS attempt_id, NULL::text AS locked_by, NULL::integer AS lease_renewals
                   FROM %4$s e
                  WHERE e.status = 'PENDING'
                    AND e.next_attempt_at <= now()
                    AND e.execution_created_at >= :activeFrom
                    AND e.task_type = ANY(CAST(:types AS text[]))
                    AND NOT EXISTS (SELECT 1 FROM tc_task_attempt a
                                     WHERE a.execution_created_at >= :activeFrom
                                       AND a.execution_created_at = e.execution_created_at
                                       AND a.target_id = e.id
                                       AND a.finished_at IS NULL)
                  ORDER BY %5$s
                  LIMIT :limit
                    FOR UPDATE OF e SKIP LOCKED)
                """.formatted(
                        level.name(),
                        phase,
                        contractShape,
                        level.table(),
                        ordering.orderBy,
                        level.table() + "_candidates",
                        level == TaskLevel.TASK ? TASK_ORIGIN : NO_ORIGIN,
                        parent(level));
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

    private static String parent(final TaskLevel level) {
        return switch (level) {
            case TASK -> NO_PARENT;
            case BATCH -> BATCH_PARENT;
            case UNIT -> UNIT_PARENT;
        };
    }

    /** Порядок, в котором выбирается готовая работа в этом такте. */
    enum Ordering {
        DEADLINE("e.claim_order_at"),
        PRIORITY("e.priority, e.next_attempt_at"),
        FIFO("e.next_attempt_at");

        private final String orderBy;

        Ordering(final String orderBy) {
            this.orderBy = orderBy;
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
