package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Открытие попыток в транзакции такта захвата (docs/adr/0046-lease-in-attempt.md): сущности
 * становятся {@code RUNNING}, для каждой вставляется строка попытки с новым токеном и арендой
 * по настройкам метода процессора (docs/adr/0118-attempt-settings-per-processor-method.md),
 * номер попытки и признак повтора читаются из строк попыток той же фазы
 * (docs/adr/0112-attempt-number-in-processing-context.md).
 *
 * <p>Открытие пачечное: на такт одна пометка на каждую таблицу уровня, одна вставка и одно чтение
 * истории, все через {@code unnest}. Число запросов не зависит от размера такта. Каждый запрос
 * держит условие {@code execution_created_at >= active_from} на каждой своей таблице — ключи
 * из массива планировщик для отсечения партиций не использует (docs/adr/0101-active-partition-horizon.md).
 */
class TaskAttemptOpener {

    private static final String MARK_RUNNING = """
            UPDATE {table} e
               SET status = 'RUNNING', updated_at = now()
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:id AS uuid[]))
                   AS x(execution_created_at, id)
             WHERE e.execution_created_at >= :activeFrom
               AND e.execution_created_at = x.execution_created_at AND e.id = x.id
            """;

    /**
     * Задачи: при первом захвате форма контракта и фаза пишутся вместе с пометкой
     * (docs/adr/0026-contract-shape-marking.md), у прочих в массиве пусто и колонки не меняются.
     */
    private static final String MARK_TASKS_RUNNING = """
            UPDATE tc_task e
               SET status = 'RUNNING',
                   contract_shape = coalesce(x.contract_shape, e.contract_shape),
                   phase = coalesce(x.phase, e.phase),
                   updated_at = now()
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:id AS uuid[]),
                          CAST(:contractShape AS text[]), CAST(:phase AS text[]))
                   AS x(execution_created_at, id, contract_shape, phase)
             WHERE e.execution_created_at >= :activeFrom
               AND e.execution_created_at = x.execution_created_at AND e.id = x.id
            """;

    private static final String INSERT_ATTEMPTS = """
            INSERT INTO tc_task_attempt (
                id, created_at, updated_at, execution_created_at, level, target_id, phase,
                locked_by, locked_until, lease_token, lease_renewals, started_at)
            SELECT x.id, now(), now(), x.execution_created_at, x.level, x.target_id, x.phase,
                   :lockedBy, now() + make_interval(secs => x.lease), x.lease_token, 0, now()
              FROM unnest(CAST(:id AS uuid[]), CAST(:executionCreatedAt AS timestamptz[]),
                          CAST(:level AS text[]), CAST(:targetId AS uuid[]), CAST(:phase AS text[]),
                          CAST(:lease AS float8[]), CAST(:leaseToken AS uuid[]))
                   AS x(id, execution_created_at, level, target_id, phase, lease, lease_token)
            """;

    /**
     * Попытки той же фазы у каждой сущности: в текущем цикле повторов и за всю историю, считая
     * только что вставленную.
     */
    private static final String ATTEMPT_HISTORY = """
            SELECT x.id,
                   count(*) FILTER (WHERE a.started_at >= coalesce(t.retry_cycle_started_at,
                                                                   b.retry_cycle_started_at,
                                                                   u.retry_cycle_started_at)) AS in_cycle,
                   count(*) AS in_history
              FROM unnest(CAST(:level AS text[]), CAST(:executionCreatedAt AS timestamptz[]),
                          CAST(:id AS uuid[]), CAST(:phase AS text[]))
                   AS x(level, execution_created_at, id, phase)
              JOIN tc_task_attempt a ON a.execution_created_at >= :activeFrom
                   AND a.execution_created_at = x.execution_created_at
                   AND a.level = x.level AND a.target_id = x.id AND a.phase = x.phase
              LEFT JOIN tc_task t ON x.level = 'TASK' AND t.execution_created_at >= :activeFrom
                   AND t.execution_created_at = x.execution_created_at AND t.id = x.id
              LEFT JOIN tc_task_batch b ON x.level = 'BATCH' AND b.execution_created_at >= :activeFrom
                   AND b.execution_created_at = x.execution_created_at AND b.id = x.id
              LEFT JOIN tc_task_unit u ON x.level = 'UNIT' AND u.execution_created_at >= :activeFrom
                   AND u.execution_created_at = x.execution_created_at AND u.id = x.id
             GROUP BY x.id
            """;

    private final UuidV7 ids;

    private final AttemptSettingsSource settings;

    private final String instance;

    /**
     * Собирает открытие попыток.
     *
     * @param ids генератор идентификаторов
     * @param settings настройки попыток по типу и методу процессора
     * @param instance имя этого экземпляра — пишется в {@code locked_by}
     */
    TaskAttemptOpener(final UuidV7 ids, final AttemptSettingsSource settings, final String instance) {
        this.ids = ids;
        this.settings = settings;
        this.instance = instance;
    }

    /**
     * Что открыть для кандидата такта. Фаза — записанная у сущности; у задачи до первого захвата её
     * нет, и берётся первая фаза формы контракта процессора — тогда же форма и фаза пишутся в задачу
     * (docs/adr/0026-contract-shape-marking.md).
     *
     * @param entity сущность, заблокированная тактом
     * @param taskType тип задачи
     * @param recordedPhase фаза сущности; у задачи до первого захвата пусто
     * @param recordedShape записанная форма контракта задачи; пусто до первого захвата и у детей
     * @param shape форма контракта текущего процессора
     * @param snapshot снимок сущности для воркера
     * @return попытка, которую нужно открыть
     */
    static Opening opening(
            final TaskEntityRef entity,
            final String taskType,
            final TaskPhase recordedPhase,
            final ContractShape recordedShape,
            final ContractShape shape,
            final ClaimedEntity snapshot) {
        final TaskPhase phase = recordedPhase == null ? shape.firstPhase() : recordedPhase;
        final boolean firstClaim = entity.level() == TaskLevel.TASK && recordedShape == null;
        return new Opening(entity, taskType, phase, firstClaim, shape, snapshot);
    }

    /**
     * Открывает попытки над заблокированными тактом сущностями.
     *
     * @param jdbc клиент в транзакции такта
     * @param activeFrom граница активных партиций такта
     * @param openings что открыть, в порядке захвата
     * @return захваченная работа в том же порядке
     */
    List<ClaimedAttempt> open(final JdbcClient jdbc, final Instant activeFrom, final List<Opening> openings) {
        if (openings.isEmpty()) {
            return List.of();
        }
        final List<AttemptLease> leases = new ArrayList<>(openings.size());
        for (final Opening opening : openings) {
            final LeaseSettings lease = this.settings
                    .settings(opening.taskType(), opening.entity().level(), opening.phase())
                    .lease();
            leases.add(new AttemptLease(
                    this.ids.next(),
                    UUID.randomUUID(),
                    lease.duration(),
                    lease.heartbeatInterval(),
                    lease.maxRenewals()));
        }
        mark(jdbc, activeFrom, openings);
        this.insert(jdbc, openings, leases);
        final Map<UUID, AttemptHistory> history = history(jdbc, activeFrom, openings);
        final List<ClaimedAttempt> claimed = new ArrayList<>(openings.size());
        for (int i = 0; i < openings.size(); i++) {
            final Opening opening = openings.get(i);
            claimed.add(new ClaimedAttempt(
                    leases.get(i),
                    opening.entity(),
                    opening.taskType(),
                    opening.phase(),
                    history.get(opening.entity().id()),
                    opening.snapshot()));
        }
        return claimed;
    }

    private static void mark(final JdbcClient jdbc, final Instant activeFrom, final List<Opening> openings) {
        final Map<TaskLevel, List<Opening>> byLevel = new EnumMap<>(TaskLevel.class);
        openings.forEach(opening -> byLevel.computeIfAbsent(opening.entity().level(), level -> new ArrayList<>())
                .add(opening));
        byLevel.forEach((level, entities) -> {
            final JdbcClient.StatementSpec statement = jdbc.sql(
                            level == TaskLevel.TASK
                                    ? MARK_TASKS_RUNNING
                                    : MARK_RUNNING.replace(TaskRetries.TABLE, level.table()))
                    .param("activeFrom", TaskSql.parameter(activeFrom))
                    .param(
                            "executionCreatedAt",
                            TaskSql.column(
                                    entities, o -> TaskSql.parameter(o.entity().executionCreatedAt())))
                    .param("id", TaskSql.column(entities, o -> o.entity().id()));
            if (level == TaskLevel.TASK) {
                statement
                        .param(
                                "contractShape",
                                TaskSql.column(
                                        entities,
                                        o -> o.firstClaim() ? o.shape().name() : null))
                        .param(
                                "phase",
                                TaskSql.column(
                                        entities,
                                        o -> o.firstClaim() ? o.phase().name() : null));
            }
            statement.update();
        });
    }

    private void insert(final JdbcClient jdbc, final List<Opening> openings, final List<AttemptLease> leases) {
        jdbc.sql(INSERT_ATTEMPTS)
                .param("id", TaskSql.column(leases, AttemptLease::attemptId))
                .param(
                        "executionCreatedAt",
                        TaskSql.column(
                                openings, o -> TaskSql.parameter(o.entity().executionCreatedAt())))
                .param("level", TaskSql.column(openings, o -> o.entity().level().name()))
                .param("targetId", TaskSql.column(openings, o -> o.entity().id()))
                .param("phase", TaskSql.column(openings, o -> o.phase().name()))
                .param("lease", TaskSql.column(leases, lease -> TaskSql.seconds(lease.duration())))
                .param("leaseToken", TaskSql.column(leases, AttemptLease::token))
                .param("lockedBy", this.instance)
                .update();
    }

    private static Map<UUID, AttemptHistory> history(
            final JdbcClient jdbc, final Instant activeFrom, final List<Opening> openings) {
        final Map<UUID, AttemptHistory> history = new HashMap<>();
        jdbc.sql(ATTEMPT_HISTORY)
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("level", TaskSql.column(openings, o -> o.entity().level().name()))
                .param(
                        "executionCreatedAt",
                        TaskSql.column(
                                openings, o -> TaskSql.parameter(o.entity().executionCreatedAt())))
                .param("id", TaskSql.column(openings, o -> o.entity().id()))
                .param("phase", TaskSql.column(openings, o -> o.phase().name()))
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        new AttemptHistory(rs.getInt("in_cycle"), rs.getInt("in_history") > 1)))
                .list()
                .forEach(found -> history.put(found.getKey(), found.getValue()));
        return history;
    }

    /**
     * Попытка, которую нужно открыть.
     *
     * @param entity сущность, заблокированная тактом
     * @param taskType тип задачи
     * @param phase фаза попытки
     * @param firstClaim первый захват задачи — форма контракта и фаза пишутся вместе с ним
     * @param shape форма контракта процессора
     * @param snapshot снимок сущности для воркера
     */
    record Opening(
            TaskEntityRef entity,
            String taskType,
            TaskPhase phase,
            boolean firstClaim,
            ContractShape shape,
            ClaimedEntity snapshot) {}
}
