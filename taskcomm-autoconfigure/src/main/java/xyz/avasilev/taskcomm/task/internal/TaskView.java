package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskStatus;

/**
 * Реализация {@link Task} — снимок на момент чтения (docs/adr/0111-task-public-api-shape.md).
 *
 * @param row строка задачи
 * @param payload нагрузка, разобранная в тип из дженерика процессора
 * @param <T> параметры задачи
 */
record TaskView<T>(TaskRow row, T payload) implements Task<T> {

    @Override
    public UUID id() {
        return this.row.id();
    }

    @Override
    public ExecutionRef executionRef() {
        return this.row.executionRef();
    }

    @Override
    public String taskType() {
        return this.row.taskType();
    }

    @Override
    public int priority() {
        return this.row.priority();
    }

    @Override
    public TaskStatus status() {
        return this.row.status();
    }

    @Override
    public Optional<String> statusDescription() {
        return Optional.ofNullable(this.row.statusDescription());
    }

    @Override
    public Instant createdAt() {
        return this.row.createdAt();
    }

    @Override
    public Optional<Origin> origin() {
        return Optional.ofNullable(this.row.origin());
    }
}
