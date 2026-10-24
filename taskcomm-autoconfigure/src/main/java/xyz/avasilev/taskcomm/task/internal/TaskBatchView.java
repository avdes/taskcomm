package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskBatchStatus;

/**
 * Реализация {@link TaskBatch} — снимок на момент чтения (docs/adr/0111-task-public-api-shape.md).
 *
 * @param id идентификатор группы
 * @param taskId задача
 * @param executionRef дело
 * @param taskType тип задачи
 * @param payload параметры группы, разобранные в тип из дженерика процессора
 * @param priority приоритет
 * @param status статус на момент чтения
 * @param createdAt время создания
 * @param <B> параметры группы
 */
record TaskBatchView<B>(
        UUID id,
        UUID taskId,
        ExecutionRef executionRef,
        String taskType,
        B payload,
        int priority,
        TaskBatchStatus status,
        Instant createdAt)
        implements TaskBatch<B> {

    @Override
    public Optional<String> statusDescription() {
        return Optional.empty();
    }
}
