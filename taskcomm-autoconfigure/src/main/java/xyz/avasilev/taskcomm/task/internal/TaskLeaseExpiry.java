package xyz.avasilev.taskcomm.task.internal;

import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Закрытие брошенных попыток исходом {@code LEASE_LOST} (docs/adr/0067-expired-lease-closes-attempt.md)
 * и каскад после него. Для политики повторов это обычная неуспешная попытка: сущность возвращается
 * в очередь с backoff или становится {@code FAILED}.
 */
@Slf4j
class TaskLeaseExpiry {

    private static final String CLOSE_EXPIRED = """
            UPDATE tc_task_attempt
               SET status = 'LEASE_LOST', status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :attemptId AND finished_at IS NULL
            """;

    private final AttemptSettingsSource settings;

    private final TaskRetries retries;

    private final TaskChildren children;

    /**
     * Собирает закрытие брошенных попыток.
     *
     * @param dataSource {@code DataSource} стартера — для каскада
     * @param transactionManager менеджер транзакций стартера — для каскада
     * @param settings настройки попыток по типу и фазе
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     * @param random источник случайных чисел для джиттера
     */
    TaskLeaseExpiry(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final AttemptSettingsSource settings,
            final PollerLayout pollers,
            final DoubleSupplier random) {
        this.settings = settings;
        this.retries = new TaskRetries(settings, pollers, random);
        this.children = new TaskChildren(dataSource, transactionManager, pollers);
    }

    /**
     * Закрывает попытку в транзакции такта захвата. Причина различает превышение предела обработки
     * и непродлённую аренду.
     *
     * @param jdbc клиент в транзакции такта
     * @param expired брошенная попытка
     * @return {@code true}, если попытка закрыта этим вызовом
     */
    boolean close(final JdbcClient jdbc, final ExpiredAttempt expired) {
        final LeaseSettings lease = this.settings
                .settings(expired.taskType(), expired.entity().level(), expired.phase())
                .lease();
        final String reason = expired.leaseRenewals() >= lease.maxRenewals()
                ? "превышен предел обработки " + lease.maxExecutionTime()
                : "аренда истекла: экземпляр " + expired.lockedBy() + " не продлил её";
        final int closed = jdbc.sql(CLOSE_EXPIRED)
                .param("reason", reason)
                .param("executionCreatedAt", TaskSql.parameter(expired.entity().executionCreatedAt()))
                .param("attemptId", expired.attemptId())
                .update();
        if (closed == 1) {
            this.retries.afterUnsuccessful(
                    jdbc, new TaskRetries.Target(expired.entity(), expired.taskType(), expired.phase()), true, null);
        }
        return closed == 1;
    }

    /**
     * Каскад после коммита такта — те же шаги, что после закрытия воркером: дети неуспешного
     * раскрытия становятся {@code ABANDONED}, закрытый ребёнок отдаёт родителя в сборку, если стал
     * терминальным последним. Ошибка шага не мешает захвату — его доведёт подстраховочный поллер.
     *
     * @param expired закрытая попытка
     */
    void cascade(final ExpiredAttempt expired) {
        try {
            if (expired.phase() == TaskPhase.EXPAND) {
                this.children.settle(expired.entity(), expired.attemptId(), false);
            }
            if (expired.parent() != null) {
                this.children.parentReady(expired.entity(), expired.parent(), expired.taskType());
            }
        } catch (final RuntimeException failure) {
            log.warn("Брошенная попытка {} закрыта, но каскад после неё не доведён", expired.attemptId(), failure);
        }
    }
}
