package xyz.avasilev.taskcomm.task.internal;

import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.CurrentExecution;
import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskStatus;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Выполнение одной захваченной попытки: вызов процессора и закрытие попытки исходом.
 *
 * <p>Воркер перехватывает всё, что бросил процессор потребителя: иначе чужой код убил бы поток
 * пула. Исход — по правилам docs/rules/tasks.md: нормальный возврат — успех, а с вызванным
 * {@code skip(reason)} — пропуск; исключение — неуспех, перебивающий пропуск, с повтором, если
 * процессор не вызвал {@code doNotRetry(reason)}. Возвращённое значение сохраняется, если оно
 * не {@code null} (docs/adr/0058-task-result-storage.md).
 *
 * <p>На время вызова поток привязан к делу сущности (docs/rules/execution.md): задача,
 * поставленная из процессора в этом потоке, попадает в то же дело и получает указатель
 * на создателя.
 */
@Slf4j
class TaskWorker {

    private final TaskProcessorRegistry registry;

    private final TaskPayloadCodec codec;

    private final TaskAttemptCloser closer;

    private final TaskHeartbeat heartbeat;

    TaskWorker(
            final TaskProcessorRegistry registry,
            final TaskPayloadCodec codec,
            final TaskAttemptCloser closer,
            final TaskHeartbeat heartbeat) {
        this.registry = registry;
        this.codec = codec;
        this.closer = closer;
        this.heartbeat = heartbeat;
    }

    /**
     * Выполняет попытку. Ничего не бросает: ошибка процессора становится неуспешной попыткой,
     * а ошибка закрытия — WARN; попытку тогда закроет переподхват истёкшей аренды.
     *
     * @param attempt захваченная работа
     */
    void execute(final ClaimedAttempt attempt) {
        this.heartbeat.track(attempt);
        try {
            final RegisteredProcessor processor = this.registry
                    .find(attempt.taskType())
                    .orElseThrow(() -> new IllegalStateException(
                            "Захвачена задача типа без процессора '" + attempt.taskType() + "'"));
            this.close(attempt, this.invoke(attempt, processor));
        } catch (final RuntimeException failure) {
            log.warn(
                    "Попытку {} ({} {} '{}') не удалось закрыть; её закроет переподхват истёкшей аренды.",
                    attempt.lease().attemptId(),
                    attempt.target().level(),
                    attempt.target().id(),
                    attempt.taskType(),
                    failure);
        } finally {
            this.heartbeat.release(attempt);
        }
    }

    private Outcome invoke(final ClaimedAttempt attempt, final RegisteredProcessor processor) {
        final ClaimedEntity entity = attempt.entity();
        final ExecutionRef execution =
                new ExecutionRef(entity.executionId(), attempt.target().executionCreatedAt());
        final TaskProcessingContext context =
                new TaskProcessingContext(execution, attempt.history(), () -> this.heartbeat.isLost(attempt));
        final Origin creator = new Origin(
                OriginType.valueOf(attempt.target().level().name()),
                attempt.target().id(),
                attempt.lease().attemptId(),
                OriginRelation.SPAWNED);
        try (CurrentExecution.Scope _ = CurrentExecution.bind(new CurrentExecution.Binding(execution, creator))) {
            final Object result = this.dispatch(attempt, processor, execution, context);
            return new Outcome(result, context, null);
        } catch (final Throwable failure) {
            log.warn(
                    "Процессор типа '{}' завершил попытку {} ({} {}) исключением",
                    attempt.taskType(),
                    attempt.lease().attemptId(),
                    attempt.target().level(),
                    attempt.target().id(),
                    failure);
            return new Outcome(null, context, failure);
        }
    }

    /**
     * Разбор запечатанной иерархии без ветки {@code default}: четвёртый контракт сломает компиляцию
     * здесь (docs/adr/0007-processor-contracts.md).
     */
    @SuppressWarnings("unchecked")
    private Object dispatch(
            final ClaimedAttempt attempt,
            final RegisteredProcessor processor,
            final ExecutionRef execution,
            final TaskProcessingContext context) {
        final TaskProcessor<?> contract = processor.processor();
        return switch (contract) {
            case FlatTaskProcessor<?, ?> flat ->
                ((FlatTaskProcessor<Object, ?>) flat).process(this.task(attempt, processor, execution), context);
            case UnitTaskProcessor<?, ?, ?, ?> _ -> throw notYet(attempt);
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> _ -> throw notYet(attempt);
        };
    }

    private TaskView<Object> task(
            final ClaimedAttempt attempt, final RegisteredProcessor processor, final ExecutionRef execution) {
        final ClaimedEntity entity = attempt.entity();
        return new TaskView<>(
                new TaskRow(
                        attempt.target().id(),
                        execution,
                        attempt.taskType(),
                        entity.priority(),
                        TaskStatus.RUNNING,
                        null,
                        entity.createdAt(),
                        entity.origin()),
                this.codec.read(entity.payload(), processor.types().task()));
    }

    private void close(final ClaimedAttempt attempt, final Outcome outcome) {
        if (outcome.failure() != null) {
            this.closer.fail(
                    attempt, describe(outcome.failure()), outcome.context().doNotRetryReason());
        } else if (outcome.context().skipReason() != null) {
            this.closer.skip(attempt, outcome.context().skipReason(), this.codec.writeResult(outcome.result()));
        } else {
            this.closer.succeed(attempt, this.codec.writeResult(outcome.result()));
        }
    }

    private static String describe(final Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + failure.getMessage();
    }

    /** Раскрытие и сборка ещё не подключены: задачи с детьми поллер не захватывает. */
    private static IllegalStateException notYet(final ClaimedAttempt attempt) {
        return new IllegalStateException("Задачи с подзадачами и группами пока не обрабатываются: тип '"
                + attempt.taskType() + "' не должен был быть захвачен");
    }

    /**
     * Чем кончился вызов процессора.
     *
     * @param result возвращённое значение
     * @param context контекст вызова — пропуск и запрет повтора
     * @param failure исключение процессора или {@code null}
     */
    private record Outcome(Object result, TaskProcessingContext context, Throwable failure) {}
}
