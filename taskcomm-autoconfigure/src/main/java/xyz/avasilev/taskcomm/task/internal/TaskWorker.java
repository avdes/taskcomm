package xyz.avasilev.taskcomm.task.internal;

import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.CurrentExecution;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Выполнение одной захваченной попытки: вызов метода процессора и закрытие попытки исходом.
 * Метод выбирается по уровню и фазе попытки, ничего не вычисляя (docs/adr/0069-parent-phase-column.md).
 *
 * <p>Воркер перехватывает всё, что бросил процессор потребителя: иначе чужой код убил бы поток
 * пула. Исход — по правилам docs/rules/tasks.md: нормальный возврат — успех, а с вызванным
 * {@code skip(reason)} — пропуск; исключение — неуспех, перебивающий пропуск, с повтором, если
 * процессор не вызвал {@code doNotRetry(reason)}. Возвращённое значение сохраняется, если оно
 * не {@code null} (docs/adr/0058-task-result-storage.md).
 *
 * <p>Раскрытие получает {@link TaskEmitter}: после нормального возврата — финальный сброс, потом
 * закрытие попытки успехом (docs/adr/0008-expansion-emitter.md). Сборка получает ленивый поток
 * детей последнего успешного раскрытия (docs/adr/0059-collect-contract.md).
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

    private final TaskChildren children;

    private final TaskCollector collector;

    private final UuidV7 ids;

    TaskWorker(
            final TaskProcessorRegistry registry,
            final TaskPayloadCodec codec,
            final TaskAttemptCloser closer,
            final TaskHeartbeat heartbeat,
            final TaskChildren children,
            final TaskCollector collector,
            final UuidV7 ids) {
        this.registry = registry;
        this.codec = codec;
        this.closer = closer;
        this.heartbeat = heartbeat;
        this.children = children;
        this.collector = collector;
        this.ids = ids;
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
            this.close(this.invoke(this.prepare(attempt, processor)));
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

    private Call prepare(final ClaimedAttempt attempt, final RegisteredProcessor processor) {
        final ExecutionRef execution = new ExecutionRef(
                attempt.entity().executionId(), attempt.target().executionCreatedAt());
        final TaskProcessingContext context = new TaskProcessingContext(
                execution,
                attempt.history(),
                () -> this.heartbeat.isLost(attempt) || this.heartbeat.isCancelled(attempt));
        final TaskLevel childLevel = childLevel(attempt.target().level(), processor.shape());
        final TaskEmitter emitter = attempt.phase() == TaskPhase.EXPAND
                ? new TaskEmitter(attempt, childLevel, this.children, this.codec, this.ids)
                : null;
        final UUID expansion =
                attempt.phase() == TaskPhase.COLLECT ? this.collector.latestExpansion(attempt.target()) : null;
        return new Call(attempt, processor, execution, context, new Children(childLevel, emitter, expansion));
    }

    private Outcome invoke(final Call call) {
        final ClaimedAttempt attempt = call.attempt();
        final Origin creator = new Origin(
                OriginType.valueOf(attempt.target().level().name()),
                attempt.target().id(),
                attempt.lease().attemptId(),
                OriginRelation.SPAWNED);
        try (CurrentExecution.Scope _ =
                CurrentExecution.bind(new CurrentExecution.Binding(call.execution(), creator))) {
            return new Outcome(call, this.dispatch(call), null);
        } catch (final Throwable failure) {
            log.warn(
                    "Процессор типа '{}' завершил попытку {} ({} {}, {}) исключением",
                    attempt.taskType(),
                    attempt.lease().attemptId(),
                    attempt.target().level(),
                    attempt.target().id(),
                    attempt.method().methodName(),
                    failure);
            return new Outcome(call, null, failure);
        }
    }

    /**
     * Разбор запечатанной иерархии без ветки {@code default}: четвёртый контракт сломает компиляцию
     * здесь (docs/adr/0007-processor-contracts.md).
     */
    @SuppressWarnings("unchecked")
    private Object dispatch(final Call call) {
        final TaskProcessor<?> contract = call.processor().processor();
        return switch (contract) {
            case FlatTaskProcessor<?, ?> flat -> {
                requireMethod(call, ProcessorMethod.PROCESS_TASK);
                yield ((FlatTaskProcessor<Object, ?>) flat).process(this.task(call), call.context());
            }
            case UnitTaskProcessor<?, ?, ?, ?> unit ->
                this.unitContract((UnitTaskProcessor<Object, Object, Object, Object>) unit, call);
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> batch ->
                this.batchContract((BatchTaskProcessor<Object, Object, Object, Object, Object, Object>) batch, call);
        };
    }

    private Object unitContract(final UnitTaskProcessor<Object, Object, Object, Object> processor, final Call call) {
        return switch (call.attempt().method()) {
            case EXPAND -> {
                processor.expand(this.task(call), call.children().emitter(), call.context());
                yield null;
            }
            case PROCESS_UNIT -> processor.process(this.unit(call), call.context());
            case COLLECT -> processor.collect(this.task(call), this.collectUnits(call), call.context());
            case PROCESS_TASK, EXPAND_BATCH, COLLECT_BATCH -> throw foreignMethod(call);
        };
    }

    private Object batchContract(
            final BatchTaskProcessor<Object, Object, Object, Object, Object, Object> processor, final Call call) {
        return switch (call.attempt().method()) {
            case EXPAND -> {
                processor.expand(this.task(call), call.children().emitter(), call.context());
                yield null;
            }
            case EXPAND_BATCH -> {
                processor.expandBatch(this.batch(call), call.children().emitter(), call.context());
                yield null;
            }
            case PROCESS_UNIT -> processor.process(this.unit(call), call.context());
            case COLLECT_BATCH -> processor.collectBatch(this.batch(call), this.collectUnits(call), call.context());
            case COLLECT ->
                processor.collect(
                        this.task(call),
                        this.collector.batches(
                                call.attempt().target().executionCreatedAt(),
                                call.children().expansion(),
                                call.processor().types()),
                        call.context());
            case PROCESS_TASK -> throw foreignMethod(call);
        };
    }

    private Stream<CollectedUnit<Object, Object>> collectUnits(final Call call) {
        return this.collector.units(
                call.attempt().target().executionCreatedAt(),
                call.children().expansion(),
                call.processor().types());
    }

    private TaskView<Object> task(final Call call) {
        return TaskViews.task(
                call.attempt(),
                call.execution(),
                this.codec.read(
                        call.attempt().entity().payload(),
                        call.processor().types().task()));
    }

    private TaskBatchView<Object> batch(final Call call) {
        return TaskViews.batch(
                call.attempt(),
                call.execution(),
                this.codec.read(
                        call.attempt().entity().payload(),
                        call.processor().types().batch()));
    }

    private TaskUnitView<Object> unit(final Call call) {
        return TaskViews.unit(
                call.attempt(),
                call.execution(),
                this.codec.read(
                        call.attempt().entity().payload(),
                        call.processor().types().unit()));
    }

    private void close(final Outcome outcome) {
        final Call call = outcome.call();
        final ClaimedAttempt attempt = call.attempt();
        final TaskEmitter emitter = call.children().emitter();
        if (outcome.failure() != null || call.context().skipReason() != null) {
            if (emitter != null) {
                emitter.discard();
            }
            if (outcome.failure() != null) {
                this.closer.fail(
                        attempt, describe(outcome.failure()), call.context().doNotRetryReason());
            } else {
                this.closer.skip(attempt, call.context().skipReason(), this.codec.writeResult(outcome.result()));
            }
        } else {
            final String result = this.codec.writeResult(outcome.result());
            switch (attempt.phase()) {
                case PROCESS -> this.closer.succeed(attempt, result);
                case EXPAND -> {
                    emitter.finish();
                    this.closer.expanded(attempt, emitter.flushed());
                }
                case COLLECT ->
                    this.closer.collected(
                            attempt,
                            result,
                            call.children().level(),
                            call.children().expansion());
            }
        }
    }

    /**
     * Уровень детей сущности: у задачи — группы при форме {@code BATCH}, иначе подзадачи; у группы —
     * подзадачи. У подзадачи детей нет.
     */
    private static TaskLevel childLevel(final TaskLevel level, final ContractShape shape) {
        final TaskLevel child;
        if (level == TaskLevel.TASK) {
            child = shape == ContractShape.BATCH ? TaskLevel.BATCH : TaskLevel.UNIT;
        } else {
            child = level == TaskLevel.BATCH ? TaskLevel.UNIT : null;
        }
        return child;
    }

    private static String describe(final Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + failure.getMessage();
    }

    private static void requireMethod(final Call call, final ProcessorMethod method) {
        if (call.attempt().method() != method) {
            throw foreignMethod(call);
        }
    }

    /** Попытка метода, которого у контракта нет: форма контракта разошлась с процессором. */
    private static IllegalStateException foreignMethod(final Call call) {
        return new IllegalStateException("У процессора типа '" + call.attempt().taskType() + "' формы "
                + call.processor().shape() + " нет метода " + call.attempt().method() + " — попытка "
                + call.attempt().lease().attemptId() + " захвачена по другой форме контракта");
    }

    /**
     * Дети попытки.
     *
     * @param level уровень детей или {@code null} у подзадачи
     * @param emitter приёмник детей у раскрытия, иначе {@code null}
     * @param expansion собираемая попытка раскрытия у сборки, иначе {@code null}
     */
    private record Children(TaskLevel level, TaskEmitter emitter, UUID expansion) {}

    /**
     * Всё для вызова процессора.
     *
     * @param attempt захваченная работа
     * @param processor процессор типа
     * @param execution дело сущности
     * @param context контекст вызова — пропуск и запрет повтора
     * @param children дети попытки
     */
    private record Call(
            ClaimedAttempt attempt,
            RegisteredProcessor processor,
            ExecutionRef execution,
            TaskProcessingContext context,
            Children children) {}

    /**
     * Чем кончился вызов процессора.
     *
     * @param call вызов
     * @param result возвращённое значение
     * @param failure исключение процессора или {@code null}
     */
    private record Outcome(Call call, Object result, Throwable failure) {}
}
