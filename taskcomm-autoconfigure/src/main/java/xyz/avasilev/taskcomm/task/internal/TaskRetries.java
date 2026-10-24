package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Что делается с сущностью после неуспешной попытки — закрытой воркером исходом {@code FAILED}
 * или переподхватом исходом {@code LEASE_LOST}: для политики повторов это одно и то же
 * (docs/adr/0067-expired-lease-closes-attempt.md). Сущность возвращается в {@code PENDING}
 * с backoff или, если лимит исчерпан, становится {@code FAILED}.
 *
 * <p>Лимит — свой у каждой фазы (docs/adr/0116-attempt-limit-per-phase.md): попытки считаются
 * по строкам попыток той же фазы начиная с {@code retry_cycle_started_at} сущности
 * (docs/adr/0043-manual-restart-resets-attempts.md), и успешное раскрытие не съедает попыток
 * сборки.
 *
 * <p>{@code claim_order_at} пересчитывается в том же выражении, что {@code next_attempt_at},
 * по шагу дедлайна поллера этого типа (docs/adr/0063-claim-ordering-and-pollers.md). Методы
 * работают в транзакции вызывающего.
 */
@RequiredArgsConstructor
class TaskRetries {

    /** Причина {@code FAILED} у сущности, исчерпавшей лимит. */
    static final String EXHAUSTED = "попытки исчерпаны";

    /**
     * Место имени таблицы уровня в тексте запроса. Подставляется имя из {@link TaskLevel}, а не
     * ввод: запрос по одной из трёх таблиц сущностей пишется один раз.
     */
    static final String TABLE = "{table}";

    private static final String COUNT_ATTEMPTS = """
            SELECT count(*)
              FROM tc_task_attempt a
             WHERE a.execution_created_at = :executionCreatedAt
               AND a.level = :level
               AND a.target_id = :targetId
               AND a.phase = :phase
               AND a.started_at >= (SELECT e.retry_cycle_started_at FROM {table} e
                                     WHERE e.execution_created_at = :executionCreatedAt AND e.id = :targetId)
            """;

    private static final String REQUEUE = """
            UPDATE {table}
               SET status = 'PENDING',
                   next_attempt_at = now() + make_interval(secs => :delay),
                   claim_order_at = now() + make_interval(secs => :delay + priority * :deadlineStep),
                   updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId AND status = 'RUNNING'
            """;

    private static final String FAIL = """
            UPDATE {table}
               SET status = 'FAILED', status_description = :reason, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId AND status = 'RUNNING'
            """;

    private final AttemptSettingsSource settings;

    private final PollerLayout pollers;

    private final DoubleSupplier random;

    /**
     * Возвращает сущность в очередь с backoff либо делает её {@code FAILED}.
     *
     * @param jdbc клиент в транзакции, закрывшей попытку
     * @param target сущность закрытой попытки
     * @param retryable {@code false}, если повтор запрещён процессором
     * @param reason причина {@code FAILED}, если повтор запрещён
     */
    void afterUnsuccessful(final JdbcClient jdbc, final Target target, final boolean retryable, final String reason) {
        if (!retryable) {
            this.fail(jdbc, target.entity(), reason);
        } else {
            final RetryPolicy policy = this.settings
                    .settings(target.taskType(), target.entity().level(), target.phase())
                    .retry();
            final int attempts = this.countAttempts(jdbc, target);
            if (policy.exhausted(attempts)) {
                this.fail(jdbc, target.entity(), EXHAUSTED);
            } else {
                this.requeue(jdbc, target, policy.delay(attempts, this.random));
            }
        }
    }

    private int countAttempts(final JdbcClient jdbc, final Target target) {
        final TaskEntityRef entity = target.entity();
        return jdbc.sql(COUNT_ATTEMPTS.replace(TABLE, entity.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("level", entity.level().name())
                .param("targetId", entity.id())
                .param("phase", target.phase().name())
                .query(Integer.class)
                .single();
    }

    private void requeue(final JdbcClient jdbc, final Target target, final Duration delay) {
        final TaskEntityRef entity = target.entity();
        jdbc.sql(REQUEUE.replace(TABLE, entity.level().table()))
                .param("delay", TaskSql.seconds(delay))
                .param("deadlineStep", TaskSql.seconds(this.pollers.deadlineStep(target.taskType())))
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("targetId", entity.id())
                .update();
    }

    private void fail(final JdbcClient jdbc, final TaskEntityRef entity, final String reason) {
        jdbc.sql(FAIL.replace(TABLE, entity.level().table()))
                .param("reason", reason)
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("targetId", entity.id())
                .update();
    }

    /**
     * Сущность закрытой попытки.
     *
     * @param entity ключ строки сущности
     * @param taskType тип задачи
     * @param phase фаза закрытой попытки
     */
    record Target(TaskEntityRef entity, String taskType, TaskPhase phase) {}
}
