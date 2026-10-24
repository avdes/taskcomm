package xyz.avasilev.taskcomm.task.internal;

import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Закрытие попытки воркером. Закрытие попытки, запись результата и правка сущности — одна
 * транзакция (docs/adr/0073-result-written-with-attempt-close.md).
 *
 * <p><b>Закрытие условно по двум признакам</b> — токену аренды и {@code finished_at IS NULL}
 * (docs/adr/0067-expired-lease-closes-attempt.md). Одного токена мало: переподхват пишет
 * исход в ту же строку, а новый захват — в новую, и токен в строке опоздавшего воркера не меняется
 * никогда. Не затронута ни одна строка — попытку уже закрыли: воркер пишет WARN и молча
 * не сохраняет ни исхода, ни результата, ни сущности. Исключение бросать нельзя — работа
 * выполнена, ошибки в коде потребителя нет.
 */
@Slf4j
class TaskAttemptCloser {

    private static final String CLOSE = """
            UPDATE tc_task_attempt
               SET status = :status, status_description = :reason, finished_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt
               AND id = :attemptId
               AND lease_token = :leaseToken
               AND finished_at IS NULL
            """;

    private static final String INSERT_RESULT = """
            INSERT INTO tc_task_result (
                id, created_at, updated_at, execution_created_at, attempt_id, level, target_id, result)
            VALUES (:id, now(), now(), :executionCreatedAt, :attemptId, :level, :targetId, CAST(:result AS jsonb))
            """;

    private static final String SUCCEED = """
            UPDATE {table}
               SET status = 'SUCCEEDED', status_description = NULL, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId AND status = 'RUNNING'
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final UuidV7 ids;

    private final TaskRetries retries;

    /**
     * Собирает закрытие попыток.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param settings настройки попыток по типу и фазе
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     * @param random источник случайных чисел для джиттера
     */
    TaskAttemptCloser(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final AttemptSettingsSource settings,
            final PollerLayout pollers,
            final DoubleSupplier random) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.ids = ids;
        this.retries = new TaskRetries(settings, pollers, random);
    }

    /**
     * Закрывает попытку обработки успехом и делает сущность {@code SUCCEEDED}. Результат пишется,
     * только если он не {@code null}: отсутствие результата — отсутствие строки
     * (docs/adr/0058-task-result-storage.md).
     *
     * <p>Пока здесь только фаза обработки — у плоской задачи и подзадачи она терминальна. Успех
     * раскрытия и сборки переводит родителя и детей по своим правилам
     * (docs/adr/0068-expansion-children-statuses.md, docs/adr/0069-parent-phase-column.md).
     *
     * @param attempt захваченная работа
     * @param result результат в JSON или {@code null}
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     * @throws IllegalArgumentException если это не попытка обработки
     */
    boolean succeed(final ClaimedAttempt attempt, final String result) {
        if (attempt.phase() != TaskPhase.PROCESS) {
            throw new IllegalArgumentException("Успех фазы " + attempt.phase() + " закрывается по правилам своей фазы");
        }
        return this.close(attempt, AttemptStatus.SUCCEEDED, null, () -> {
            if (result != null) {
                this.jdbc
                        .sql(INSERT_RESULT)
                        .param("id", this.ids.next())
                        .param(
                                "executionCreatedAt",
                                TaskSql.parameter(attempt.target().executionCreatedAt()))
                        .param("attemptId", attempt.lease().attemptId())
                        .param("level", attempt.target().level().name())
                        .param("targetId", attempt.target().id())
                        .param("result", result)
                        .update();
            }
            this.jdbc
                    .sql(SUCCEED.replace(
                            TaskRetries.TABLE, attempt.target().level().table()))
                    .param(
                            "executionCreatedAt",
                            TaskSql.parameter(attempt.target().executionCreatedAt()))
                    .param("targetId", attempt.target().id())
                    .update();
        });
    }

    /**
     * Закрывает попытку неуспехом. Сущность возвращается в очередь с backoff либо, если лимит
     * исчерпан или повтор запрещён, становится {@code FAILED}.
     *
     * @param attempt захваченная работа
     * @param reason причина — текст исключения или причина запрета повтора
     * @param retryable {@code false}, если процессор запретил повтор
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     */
    boolean fail(final ClaimedAttempt attempt, final String reason, final boolean retryable) {
        return this.close(
                attempt,
                AttemptStatus.FAILED,
                reason,
                () -> this.retries.afterUnsuccessful(
                        this.jdbc,
                        new TaskRetries.Target(attempt.target(), attempt.taskType(), attempt.phase()),
                        retryable,
                        reason));
    }

    private boolean close(
            final ClaimedAttempt attempt, final AttemptStatus status, final String reason, final Runnable afterClose) {
        final boolean closed = Boolean.TRUE.equals(this.transactions.execute(transaction -> {
            final int updated = this.jdbc
                    .sql(CLOSE)
                    .param("status", status.name())
                    .param("reason", reason)
                    .param(
                            "executionCreatedAt",
                            TaskSql.parameter(attempt.target().executionCreatedAt()))
                    .param("attemptId", attempt.lease().attemptId())
                    .param("leaseToken", attempt.lease().token())
                    .update();
            if (updated == 0) {
                return false;
            }
            afterClose.run();
            return true;
        }));
        if (!closed) {
            log.warn(
                    "Попытка {} ({} {} '{}') уже закрыта — аренда истекла раньше, чем воркер закончил. "
                            + "Исход {} и результат не сохранены; работа будет или уже была выполнена повторно.",
                    attempt.lease().attemptId(),
                    attempt.target().level(),
                    attempt.target().id(),
                    attempt.taskType(),
                    status);
        }
        return closed;
    }
}
