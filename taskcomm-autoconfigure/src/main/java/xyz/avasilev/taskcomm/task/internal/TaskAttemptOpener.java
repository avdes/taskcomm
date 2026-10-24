package xyz.avasilev.taskcomm.task.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Открытие попытки в транзакции такта захвата (docs/adr/0046-lease-in-attempt.md): сущность
 * становится {@code RUNNING}, для неё вставляется строка попытки с новым токеном и арендой
 * по настройкам метода процессора (docs/adr/0118-attempt-settings-per-processor-method.md),
 * номер попытки и признак повтора читаются из строк попыток той же фазы
 * (docs/adr/0112-attempt-number-in-processing-context.md).
 */
class TaskAttemptOpener {

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
     * Открывает попытку над заблокированной тактом сущностью.
     *
     * @param jdbc клиент в транзакции такта
     * @param entity сущность
     * @param taskType тип задачи
     * @param phase фаза попытки
     * @param firstClaim первый захват задачи — форма контракта и фаза пишутся вместе с ним
     * @param shape форма контракта процессора
     * @param snapshot снимок сущности для воркера
     * @return захваченная работа
     */
    ClaimedAttempt open(
            final JdbcClient jdbc,
            final TaskEntityRef entity,
            final String taskType,
            final TaskPhase phase,
            final boolean firstClaim,
            final ContractShape shape,
            final ClaimedEntity snapshot) {
        jdbc.sql(
                        firstClaim
                                ? MARK_TASK_RUNNING_FIRST_TIME
                                : MARK_RUNNING.replace(
                                        TaskRetries.TABLE, entity.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("targetId", entity.id())
                .param("contractShape", shape.name())
                .param("phase", phase.name())
                .update();
        final LeaseSettings lease =
                this.settings.settings(taskType, entity.level(), phase).lease();
        final AttemptLease attempt = new AttemptLease(
                this.ids.next(), UUID.randomUUID(), lease.duration(), lease.heartbeatInterval(), lease.maxRenewals());
        jdbc.sql(INSERT_ATTEMPT)
                .param("id", attempt.attemptId())
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("level", entity.level().name())
                .param("targetId", entity.id())
                .param("phase", phase.name())
                .param("lockedBy", this.instance)
                .param("lease", TaskSql.seconds(attempt.duration()))
                .param("leaseToken", attempt.token())
                .update();
        return new ClaimedAttempt(attempt, entity, taskType, phase, history(jdbc, entity, phase), snapshot);
    }

    private static AttemptHistory history(final JdbcClient jdbc, final TaskEntityRef entity, final TaskPhase phase) {
        return jdbc.sql(ATTEMPT_HISTORY.replace(
                        TaskRetries.TABLE, entity.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("level", entity.level().name())
                .param("targetId", entity.id())
                .param("phase", phase.name())
                .query((rs, rowNum) -> new AttemptHistory(rs.getInt("in_cycle"), rs.getInt("in_history") > 1))
                .single();
    }
}
