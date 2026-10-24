package xyz.avasilev.taskcomm.task.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
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

            SELECT * FROM task_candidates
            UNION ALL SELECT * FROM batch_candidates
            UNION ALL SELECT * FROM unit_candidates
            UNION ALL SELECT * FROM expired
            """;

    /** Указатель на создателя есть только у задачи (docs/adr/0064-entity-origin-links.md). */
    private static final String TASK_ORIGIN = "e.origin_type, e.origin_id, e.origin_attempt_id, e.origin_relation";

    private static final String NO_ORIGIN =
            "NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id, "
                    + "NULL::text AS origin_relation";

    private static final String CLOSE_EXPIRED = """
            UPDATE tc_task_attempt
               SET status = 'LEASE_LOST', status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId AND finished_at IS NULL
            """;

    private static final String MARK_RUNNING = """
            UPDATE {table}
               SET status = 'RUNNING', updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId
            """;

    /** Первый захват задачи: форма контракта и фаза пишутся вместе (docs/adr/0026-contract-shape-marking.md). */
    private static final String MARK_TASK_RUNNING_FIRST_TIME = """
            UPDATE tc_task
               SET status = 'RUNNING', contract_shape = :contractShape, phase = :phase, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId
            """;

    private static final String INSERT_ATTEMPT = """
            INSERT INTO tc_task_attempt (
                id, created_at, updated_at, execution_created_at, level, target_id, phase,
                locked_by, locked_until, lease_token, lease_renewals, started_at)
            VALUES (:id, now(), now(), :executionCreatedAt, :level, :targetId, :phase,
                    :lockedBy, now() + make_interval(secs => :lease), :leaseToken, 0, now())
            """;

    /**
     * Попытки той же фазы: в текущем цикле повторов и за всю историю, считая только что
     * вставленную.
     */
    private static final String ATTEMPT_HISTORY = """
            SELECT count(*) FILTER (WHERE a.started_at >= e.retry_cycle_started_at) AS in_cycle,
                   count(*) AS in_history
              FROM tc_task_attempt a
              JOIN {table} e ON e.execution_created_at = a.execution_created_at AND e.id = a.target_id
             WHERE a.execution_created_at = :executionCreatedAt
               AND a.level = :level
               AND a.target_id = :targetId
               AND a.phase = :phase
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final UuidV7 ids;

    private final AttemptSettingsSource settings;

    private final TaskRetries retries;

    private final DoubleSupplier random;

    private final String instance;

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
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.ids = ids;
        this.settings = settings;
        this.retries = new TaskRetries(settings, pollers, random);
        this.random = random;
        this.instance = instance;
    }

    /**
     * Один такт захвата поллера: закрывает до {@code batchSize} брошенных попыток и захватывает
     * до {@code limit} готовых сущностей.
     *
     * @param poller поллер, от имени которого идёт захват
     * @param types типы, которые поллер вправе захватывать, с формой контракта их процессоров;
     *     задача, начатая по другой форме, не захватывается
     * @param limit сколько захватить, не больше свободных воркеров
     * @return захваченная работа, в порядке формы захвата
     */
    List<ClaimedAttempt> claim(final PollerSettings poller, final Map<String, ContractShape> types, final int limit) {
        final Ordering ordering = this.ordering(poller);
        final List<ClaimedAttempt> claimed = this.transactions.execute(status -> {
            TaskSql.forceCustomPlan(this.jdbc);
            final List<Row> rows = this.readBranches(ordering, types, limit, poller.batchSize());
            rows.stream()
                    .filter(Expired.class::isInstance)
                    .map(Expired.class::cast)
                    .forEach(this::closeExpired);
            return rows.stream()
                    .filter(Candidate.class::isInstance)
                    .map(Candidate.class::cast)
                    .filter(candidate ->
                            candidate.shape() == null || candidate.shape().equals(types.get(candidate.taskType())))
                    .sorted(Comparator.comparing(Candidate::key, ordering.comparator()))
                    .limit(limit)
                    .map(candidate -> this.start(candidate, types.get(candidate.taskType())))
                    .toList();
        });
        return claimed == null ? List.of() : claimed;
    }

    private List<Row> readBranches(
            final Ordering ordering, final Map<String, ContractShape> types, final int limit, final int expiredLimit) {
        final Instant activeFrom = TaskPartitionHorizon.activeFrom(this.jdbc);
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

    private void closeExpired(final Expired expired) {
        final LeaseSettings lease = this.settings
                .settings(expired.taskType(), expired.entity().level(), expired.phase())
                .lease();
        final String reason = expired.leaseRenewals() >= lease.maxRenewals()
                ? "превышен предел обработки " + lease.maxExecutionTime()
                : "аренда истекла: экземпляр " + expired.lockedBy() + " не продлил её";
        final int closed = this.jdbc
                .sql(CLOSE_EXPIRED)
                .param("reason", reason)
                .param("executionCreatedAt", TaskSql.parameter(expired.entity().executionCreatedAt()))
                .param("attemptId", expired.attemptId())
                .update();
        if (closed == 1) {
            this.retries.afterUnsuccessful(
                    this.jdbc,
                    new TaskRetries.Target(expired.entity(), expired.taskType(), expired.phase()),
                    true,
                    null);
        }
    }

    private ClaimedAttempt start(final Candidate candidate, final ContractShape shape) {
        final TaskEntityRef entity = candidate.entity();
        final TaskPhase phase = candidate.phase() == null ? shape.firstPhase() : candidate.phase();
        final boolean firstClaim = entity.level() == TaskLevel.TASK && candidate.shape() == null;
        this.jdbc
                .sql(
                        firstClaim
                                ? MARK_TASK_RUNNING_FIRST_TIME
                                : MARK_RUNNING.replace(
                                        TaskRetries.TABLE, entity.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("targetId", entity.id())
                .param("contractShape", shape.name())
                .param("phase", phase.name())
                .update();
        final LeaseSettings settings = this.settings
                .settings(candidate.taskType(), entity.level(), phase)
                .lease();
        final AttemptLease lease = new AttemptLease(
                this.ids.next(),
                UUID.randomUUID(),
                settings.duration(),
                settings.heartbeatInterval(),
                settings.maxRenewals());
        this.jdbc
                .sql(INSERT_ATTEMPT)
                .param("id", lease.attemptId())
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("level", entity.level().name())
                .param("targetId", entity.id())
                .param("phase", phase.name())
                .param("lockedBy", this.instance)
                .param("lease", TaskSql.seconds(lease.duration()))
                .param("leaseToken", lease.token())
                .update();
        return new ClaimedAttempt(
                lease, entity, candidate.taskType(), phase, this.history(entity, phase), candidate.snapshot());
    }

    /**
     * Номер только что вставленной попытки и признак повтора — из строк попыток той же фазы
     * (docs/adr/0112-attempt-number-in-processing-context.md).
     */
    private AttemptHistory history(final TaskEntityRef entity, final TaskPhase phase) {
        return this.jdbc
                .sql(ATTEMPT_HISTORY.replace(TaskRetries.TABLE, entity.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("level", entity.level().name())
                .param("targetId", entity.id())
                .param("phase", phase.name())
                .query((rs, rowNum) -> new AttemptHistory(rs.getInt("in_cycle"), rs.getInt("in_history") > 1))
                .single();
    }

    private static String candidates(
            final TaskLevel level, final String phase, final String contractShape, final Ordering ordering) {
        return """
                %6$s AS (SELECT '%1$s' AS level, e.execution_created_at, e.id AS target_id, e.execution_id,
                        e.task_type, %2$s AS phase, %3$s AS contract_shape, e.priority, e.next_attempt_at,
                        e.claim_order_at, e.payload::text AS payload, e.created_at, %7$s,
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
                        level.name().toLowerCase(Locale.ROOT) + "_candidates",
                        level == TaskLevel.TASK ? TASK_ORIGIN : NO_ORIGIN);
    }

    private static Row row(final ResultSet rs, final int rowNum) throws SQLException {
        final TaskEntityRef entity = new TaskEntityRef(
                TaskLevel.valueOf(rs.getString("level")),
                rs.getObject("target_id", UUID.class),
                rs.getObject("execution_created_at", OffsetDateTime.class).toInstant());
        final String phase = rs.getString("phase");
        final TaskPhase taskPhase = phase == null ? null : TaskPhase.valueOf(phase);
        final UUID attemptId = rs.getObject("attempt_id", UUID.class);
        if (attemptId != null) {
            return new Expired(
                    entity,
                    rs.getString("task_type"),
                    taskPhase,
                    attemptId,
                    rs.getString("locked_by"),
                    rs.getInt("lease_renewals"));
        }
        final String shape = rs.getString("contract_shape");
        return new Candidate(
                entity,
                rs.getString("task_type"),
                taskPhase,
                shape == null ? null : ContractShape.valueOf(shape),
                new OrderKey(
                        rs.getInt("priority"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("claim_order_at", OffsetDateTime.class).toInstant()),
                new ClaimedEntity(
                        rs.getObject("execution_id", UUID.class),
                        rs.getInt("priority"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        origin(rs),
                        rs.getString("payload")));
    }

    private static Origin origin(final ResultSet rs) throws SQLException {
        final String type = rs.getString("origin_type");
        if (type == null) {
            return null;
        }
        final String relation = rs.getString("origin_relation");
        return new Origin(
                OriginType.valueOf(type),
                rs.getObject("origin_id", UUID.class),
                rs.getObject("origin_attempt_id", UUID.class),
                OriginRelation.valueOf(relation));
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
     * @param entity ключ строки сущности попытки
     * @param taskType тип задачи
     * @param phase фаза попытки
     * @param attemptId идентификатор попытки
     * @param lockedBy экземпляр, не продливший аренду
     * @param leaseRenewals сколько раз аренда продлевалась
     */
    private record Expired(
            TaskEntityRef entity, String taskType, TaskPhase phase, UUID attemptId, String lockedBy, int leaseRenewals)
            implements Row {}
}
