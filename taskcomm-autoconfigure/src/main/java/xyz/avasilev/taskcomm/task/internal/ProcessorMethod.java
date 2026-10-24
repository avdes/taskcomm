package xyz.avasilev.taskcomm.task.internal;

import java.util.List;
import java.util.stream.Stream;
import xyz.avasilev.taskcomm.task.api.Emitter;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskUnit;

/**
 * Метод процессора — ключ настроек попыток (docs/adr/0118-attempt-settings-per-processor-method.md).
 * Уровень и фаза попытки однозначно называют метод: у фазы раскрытия группового процессора их
 * два, {@code expand} и {@code expandBatch}, и аннотация на каждом действует только на него.
 */
enum ProcessorMethod {

    /** {@code process} плоской задачи. */
    PROCESS_TASK("process", TaskLevel.TASK, TaskPhase.PROCESS),

    /** {@code expand} задачи с подзадачами или с группами. */
    EXPAND("expand", TaskLevel.TASK, TaskPhase.EXPAND),

    /** {@code expandBatch} группы. */
    EXPAND_BATCH("expandBatch", TaskLevel.BATCH, TaskPhase.EXPAND),

    /** {@code process} подзадачи. */
    PROCESS_UNIT("process", TaskLevel.UNIT, TaskPhase.PROCESS),

    /** {@code collectBatch} группы. */
    COLLECT_BATCH("collectBatch", TaskLevel.BATCH, TaskPhase.COLLECT),

    /** {@code collect} задачи. */
    COLLECT("collect", TaskLevel.TASK, TaskPhase.COLLECT);

    private final String methodName;

    private final TaskLevel level;

    private final TaskPhase phase;

    ProcessorMethod(final String methodName, final TaskLevel level, final TaskPhase phase) {
        this.methodName = methodName;
        this.level = level;
        this.phase = phase;
    }

    /**
     * Метод попытки этого уровня и фазы.
     *
     * @param level уровень сущности
     * @param phase фаза попытки
     * @return метод
     * @throws IllegalArgumentException если такого сочетания не бывает
     */
    static ProcessorMethod of(final TaskLevel level, final TaskPhase phase) {
        for (final ProcessorMethod method : values()) {
            if (method.level == level && method.phase == phase) {
                return method;
            }
        }
        throw new IllegalArgumentException("У процессора нет метода уровня " + level + " и фазы " + phase);
    }

    /**
     * Методы процессора этой формы, в порядке каскада.
     *
     * @param shape форма контракта
     * @return методы
     */
    static List<ProcessorMethod> of(final ContractShape shape) {
        return switch (shape) {
            case FLAT -> List.of(PROCESS_TASK);
            case UNIT -> List.of(EXPAND, PROCESS_UNIT, COLLECT);
            case BATCH -> List.of(EXPAND, EXPAND_BATCH, PROCESS_UNIT, COLLECT_BATCH, COLLECT);
        };
    }

    String methodName() {
        return this.methodName;
    }

    TaskLevel level() {
        return this.level;
    }

    TaskPhase phase() {
        return this.phase;
    }

    /**
     * Типы параметров метода после стирания — по ним метод ищется на классе процессора.
     *
     * @return типы параметров
     */
    Class<?>[] parameterTypes() {
        return switch (this) {
            case PROCESS_TASK -> new Class<?>[] {Task.class, ProcessingContext.class};
            case EXPAND -> new Class<?>[] {Task.class, Emitter.class, ProcessingContext.class};
            case EXPAND_BATCH -> new Class<?>[] {TaskBatch.class, Emitter.class, ProcessingContext.class};
            case PROCESS_UNIT -> new Class<?>[] {TaskUnit.class, ProcessingContext.class};
            case COLLECT_BATCH -> new Class<?>[] {TaskBatch.class, Stream.class, ProcessingContext.class};
            case COLLECT -> new Class<?>[] {Task.class, Stream.class, ProcessingContext.class};
        };
    }
}
