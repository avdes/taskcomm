package xyz.avasilev.taskcomm.task.internal;

import java.util.Optional;
import java.util.UUID;
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
 *
 * <p><b>После коммита</b> — каскад, каждый шаг своими короткими транзакциями: дети закрытой
 * попытки раскрытия переводятся из {@code AWAITING_PARENT} (docs/adr/0068-expansion-children-statuses.md);
 * закрытый ребёнок отдаёт родителя в сборку, если был последним (docs/adr/0069-parent-phase-column.md);
 * успешная сборка удаляет результаты детей, если так настроено (docs/adr/0058-task-result-storage.md).
 * Шаг, не доведённый из-за падения, доводит подстраховочный поллер.
 */
@Slf4j
class TaskAttemptCloser {

    /** Начало причины {@code FAILED} у сущности, которой процессор запретил повтор. */
    static final String DO_NOT_RETRY = "повторять запрещено: ";

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

    private static final String FINISH = """
            UPDATE {table}
               SET status = :status, status_description = :reason, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId AND status = 'RUNNING'
            """;

    /**
     * Успешное раскрытие: фаза становится {@code COLLECT}; с детьми — ждать их, без детей — сразу
     * в захват сборки.
     */
    private static final String EXPANDED = """
            UPDATE {table}
               SET status = CASE WHEN :children > 0 THEN 'AWAITING_CHILDREN' ELSE 'PENDING' END,
                   phase = 'COLLECT',
                   next_attempt_at = now(),
                   claim_order_at = now() + make_interval(secs => priority * :deadlineStep),
                   updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :targetId AND status = 'RUNNING'
            """;

    /** Агрегат детей одной попытки раскрытия — три {@code EXISTS} по индексу {@code (parent_attempt_id, status)}. */
    private static final String CHILDREN_OUTCOME = """
            SELECT EXISTS (SELECT 1 FROM {table} c
                            WHERE c.execution_created_at = :executionCreatedAt AND c.parent_attempt_id = :attemptId
                              AND c.status IN ('SUCCEEDED', 'WARNING')) AS done,
                   EXISTS (SELECT 1 FROM {table} c
                            WHERE c.execution_created_at = :executionCreatedAt AND c.parent_attempt_id = :attemptId
                              AND c.status IN ('FAILED', 'CANCELLED', 'WARNING')) AS failed,
                   EXISTS (SELECT 1 FROM {table} c
                            WHERE c.execution_created_at = :executionCreatedAt AND c.parent_attempt_id = :attemptId
                              AND c.status = 'SKIPPED') AS skipped
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final UuidV7 ids;

    private final TaskRetries retries;

    private final PollerLayout pollers;

    private final TaskChildren children;

    private final TaskResultDiscard discard;

    /**
     * Собирает закрытие попыток.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param settings настройки попыток по типу и фазе
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     * @param random источник случайных чисел для джиттера
     * @param children дети раскрытия
     * @param discard удаление результатов детей
     */
    TaskAttemptCloser(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final AttemptSettingsSource settings,
            final PollerLayout pollers,
            final DoubleSupplier random,
            final TaskChildren children,
            final TaskResultDiscard discard) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.ids = ids;
        this.retries = new TaskRetries(settings, pollers, random);
        this.pollers = pollers;
        this.children = children;
        this.discard = discard;
    }

    /**
     * Закрывает попытку обработки успехом и делает сущность {@code SUCCEEDED}. Результат пишется,
     * только если он не {@code null}: отсутствие результата — отсутствие строки
     * (docs/adr/0058-task-result-storage.md).
     *
     * @param attempt захваченная работа
     * @param result результат в JSON или {@code null}
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     * @throws IllegalArgumentException если это не попытка обработки
     */
    boolean succeed(final ClaimedAttempt attempt, final String result) {
        requirePhase(attempt, TaskPhase.PROCESS);
        return this.close(attempt, AttemptStatus.SUCCEEDED, null, () -> {
            this.insertResult(attempt, result);
            this.finish(attempt, "SUCCEEDED", null);
        });
    }

    /**
     * Закрывает попытку раскрытия успехом — после финального сброса буфера. Фаза сущности
     * становится {@code COLLECT}; с детьми сущность ждёт их в {@code AWAITING_CHILDREN}, без детей
     * сразу идёт в захват сборки: сборка запускается и при нуле детей. После коммита дети
     * переводятся в {@code PENDING}.
     *
     * <p>У отменённой сущности — своей отменой или предка — сборка не запускается: сущность
     * становится {@code CANCELLED}, её дети после коммита — сразу {@code SKIPPED}
     * (docs/adr/0100-cancel-keeps-real-outcome.md).
     *
     * @param attempt захваченная работа
     * @param childCount сколько детей записано
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     * @throws IllegalArgumentException если это не попытка раскрытия
     */
    boolean expanded(final ClaimedAttempt attempt, final int childCount) {
        requirePhase(attempt, TaskPhase.EXPAND);
        return this.close(attempt, AttemptStatus.SUCCEEDED, null, () -> {
            final Optional<TaskEntityRef> cancelledBy = TaskCancelFlags.cancelledBy(this.jdbc, attempt.target());
            if (cancelledBy.isPresent()) {
                this.finish(attempt, "CANCELLED", TaskCancelFlags.reason(attempt.target(), cancelledBy.get()));
            } else {
                this.jdbc
                        .sql(EXPANDED.replace(
                                TaskRetries.TABLE, attempt.target().level().table()))
                        .param("children", childCount)
                        .param("deadlineStep", TaskSql.seconds(this.pollers.deadlineStep(attempt.taskType())))
                        .param(
                                "executionCreatedAt",
                                TaskSql.parameter(attempt.target().executionCreatedAt()))
                        .param("targetId", attempt.target().id())
                        .update();
            }
        });
    }

    /**
     * Закрывает попытку сборки успехом. Терминальный статус сущности — по агрегату детей собранной
     * попытки раскрытия (docs/adr/0123-aggregate-without-success-fails.md): ни одного
     * незавершённого быть не может, и агрегат считается тремя {@code EXISTS}.
     *
     * @param attempt захваченная работа
     * @param result результат сборки в JSON или {@code null}
     * @param childLevel уровень собранных детей
     * @param expansionId собранная попытка раскрытия
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     * @throws IllegalArgumentException если это не попытка сборки
     */
    boolean collected(
            final ClaimedAttempt attempt, final String result, final TaskLevel childLevel, final UUID expansionId) {
        requirePhase(attempt, TaskPhase.COLLECT);
        return this.close(attempt, AttemptStatus.SUCCEEDED, null, () -> {
            this.insertResult(attempt, result);
            this.finish(attempt, this.aggregate(attempt, childLevel, expansionId), null);
        });
    }

    /**
     * Закрывает попытку пропуском: процессор вызвал {@code skip(reason)} и вернулся нормально
     * (docs/adr/0082-skip-on-every-level.md). Сущность становится {@code SKIPPED} с причиной —
     * в сборке поверх агрегата детей. Результат пишется по тому же правилу, что при успехе.
     * Пропуск раскрытия сборку не запускает, его дети становятся {@code ABANDONED}.
     *
     * @param attempt захваченная работа
     * @param reason причина пропуска от процессора
     * @param result результат в JSON или {@code null}; у раскрытия всегда {@code null}
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     */
    boolean skip(final ClaimedAttempt attempt, final String reason, final String result) {
        return this.close(attempt, AttemptStatus.SKIPPED, reason, () -> {
            this.insertResult(attempt, result);
            this.finish(attempt, "SKIPPED", reason);
        });
    }

    /**
     * Закрывает попытку неуспехом. Сущность возвращается в очередь с backoff, фаза не меняется,
     * либо, если лимит исчерпан или повтор запрещён, становится {@code FAILED}. Причина на сущности
     * различает «повторять запрещено» и «попытки исчерпаны» (docs/adr/0062-non-retryable-failure.md).
     *
     * @param attempt захваченная работа
     * @param failure причина неуспеха попытки — текст исключения
     * @param doNotRetry причина запрета повтора от процессора или {@code null}, если повтор не запрещён
     * @return {@code true}, если попытка была ещё открыта и закрыта этим вызовом
     */
    boolean fail(final ClaimedAttempt attempt, final String failure, final String doNotRetry) {
        return this.close(
                attempt,
                AttemptStatus.FAILED,
                failure,
                () -> this.retries.afterUnsuccessful(
                        this.jdbc,
                        new TaskRetries.Target(attempt.target(), attempt.taskType(), attempt.phase()),
                        doNotRetry == null,
                        doNotRetry == null ? null : DO_NOT_RETRY + doNotRetry));
    }

    /**
     * Статус родителя по детям: ни одного неуспешного — {@code SUCCEEDED}, а если все пропущены —
     * {@code SKIPPED}; есть неуспешные — {@code WARNING}, если хоть один выполнен, иначе
     * {@code FAILED}. Детей нет — {@code SUCCEEDED}.
     */
    private String aggregate(final ClaimedAttempt attempt, final TaskLevel childLevel, final UUID expansionId) {
        final ChildrenOutcome outcome = this.jdbc
                .sql(CHILDREN_OUTCOME.replace(TaskRetries.TABLE, childLevel.table()))
                .param("executionCreatedAt", TaskSql.parameter(attempt.target().executionCreatedAt()))
                .param("attemptId", expansionId)
                .query((rs, rowNum) ->
                        new ChildrenOutcome(rs.getBoolean("done"), rs.getBoolean("failed"), rs.getBoolean("skipped")))
                .single();
        final String status;
        if (outcome.failed()) {
            status = outcome.done() ? "WARNING" : "FAILED";
        } else {
            status = outcome.done() || !outcome.skipped() ? "SUCCEEDED" : "SKIPPED";
        }
        return status;
    }

    private void finish(final ClaimedAttempt attempt, final String status, final String reason) {
        this.jdbc
                .sql(FINISH.replace(TaskRetries.TABLE, attempt.target().level().table()))
                .param("status", status)
                .param("reason", reason)
                .param("executionCreatedAt", TaskSql.parameter(attempt.target().executionCreatedAt()))
                .param("targetId", attempt.target().id())
                .update();
    }

    private void insertResult(final ClaimedAttempt attempt, final String result) {
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
        if (closed) {
            this.cascade(attempt, status);
        } else {
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

    /** Шаги после коммита закрытия. Ошибка шага не отменяет закрытия: его доведёт подстраховочный поллер. */
    private void cascade(final ClaimedAttempt attempt, final AttemptStatus status) {
        try {
            if (attempt.phase() == TaskPhase.EXPAND) {
                this.settleChildren(attempt, status == AttemptStatus.SUCCEEDED);
            }
            if (attempt.phase() == TaskPhase.COLLECT && status != AttemptStatus.FAILED) {
                this.discard.afterCollect(attempt.target());
            }
            if (attempt.entity().parent() != null) {
                this.children.parentReady(attempt.target(), attempt.entity().parent(), attempt.taskType());
            }
        } catch (final RuntimeException failure) {
            log.warn(
                    "Попытка {} ({} {} '{}') закрыта, но каскад после неё не доведён; его доведёт "
                            + "подстраховочный поллер.",
                    attempt.lease().attemptId(),
                    attempt.target().level(),
                    attempt.target().id(),
                    attempt.taskType(),
                    failure);
        }
    }

    /**
     * Дети закрытой попытки раскрытия: неуспех — {@code ABANDONED}; успех — {@code PENDING}, а у
     * отменённой сущности — {@code SKIPPED}, и сущности, если она ещё ждёт детей, больше ждать
     * нечего. Отмену читает заново: она могла прийти и после коммита закрытия.
     */
    private void settleChildren(final ClaimedAttempt attempt, final boolean succeeded) {
        final TaskEntityRef target = attempt.target();
        final UUID attemptId = attempt.lease().attemptId();
        if (succeeded) {
            final Optional<TaskEntityRef> cancelledBy = TaskCancelFlags.cancelledBy(this.jdbc, target);
            if (cancelledBy.isPresent()) {
                this.children.skip(target, attemptId, TaskCancelFlags.ancestor(cancelledBy.get()));
                this.children.finishIfDone(target, attempt.taskType());
            } else {
                this.children.release(target, attemptId);
            }
        } else {
            this.children.abandon(target, attemptId);
        }
    }

    private static void requirePhase(final ClaimedAttempt attempt, final TaskPhase phase) {
        if (attempt.phase() != phase) {
            throw new IllegalArgumentException(
                    "Попытка фазы " + attempt.phase() + " закрывается не как " + phase + ", а по правилам своей фазы");
        }
    }

    /**
     * Что есть среди детей собранной попытки раскрытия.
     *
     * @param done есть выполненные — {@code SUCCEEDED} или {@code WARNING}
     * @param failed есть неуспешные — {@code FAILED}, {@code CANCELLED} или {@code WARNING}
     * @param skipped есть пропущенные
     */
    private record ChildrenOutcome(boolean done, boolean failed, boolean skipped) {}
}
