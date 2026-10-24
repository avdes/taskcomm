package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.task.api.TaskUnit;
import xyz.avasilev.taskcomm.task.api.TaskUnitStatus;

/**
 * Реализация {@link TaskUnit} — снимок на момент чтения (docs/adr/0111-task-public-api-shape.md).
 *
 * @param id идентификатор подзадачи
 * @param taskId задача
 * @param group группа или {@code null} у подзадачи без групп
 * @param executionRef дело
 * @param taskType тип задачи
 * @param payload бизнес-объект, разобранный в тип из дженерика процессора
 * @param priority приоритет
 * @param createdAt время создания
 * @param <U> бизнес-объект подзадачи
 */
record TaskUnitView<U>(
        UUID id,
        UUID taskId,
        UUID group,
        ExecutionRef executionRef,
        String taskType,
        U payload,
        int priority,
        Instant createdAt)
        implements TaskUnit<U> {

    @Override
    public Optional<UUID> batchId() {
        return Optional.ofNullable(this.group);
    }

    /** Подзадача в руках процессора всегда выполняется. */
    @Override
    public TaskUnitStatus status() {
        return TaskUnitStatus.RUNNING;
    }

    @Override
    public Optional<String> statusDescription() {
        return Optional.empty();
    }
}
