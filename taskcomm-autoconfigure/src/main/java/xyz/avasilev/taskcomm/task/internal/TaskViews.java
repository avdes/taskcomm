package xyz.avasilev.taskcomm.task.internal;

import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.task.api.TaskBatchStatus;
import xyz.avasilev.taskcomm.task.api.TaskStatus;

/**
 * Публичные представления захваченной сущности — то, что получает процессор. Статус — всегда
 * {@code RUNNING}: сущность в руках процессора выполняется.
 */
final class TaskViews {

    private TaskViews() {}

    /**
     * Задача.
     *
     * @param attempt захваченная работа над задачей
     * @param execution дело
     * @param payload нагрузка, разобранная по дженерику процессора
     * @return представление задачи
     */
    static TaskView<Object> task(final ClaimedAttempt attempt, final ExecutionRef execution, final Object payload) {
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
                payload);
    }

    /**
     * Группа.
     *
     * @param attempt захваченная работа над группой
     * @param execution дело
     * @param payload нагрузка, разобранная по дженерику процессора
     * @return представление группы
     */
    static TaskBatchView<Object> batch(
            final ClaimedAttempt attempt, final ExecutionRef execution, final Object payload) {
        final ClaimedEntity entity = attempt.entity();
        return new TaskBatchView<>(
                attempt.target().id(),
                entity.parent().taskId(),
                execution,
                attempt.taskType(),
                payload,
                entity.priority(),
                TaskBatchStatus.RUNNING,
                entity.createdAt());
    }

    /**
     * Подзадача.
     *
     * @param attempt захваченная работа над подзадачей
     * @param execution дело
     * @param payload нагрузка, разобранная по дженерику процессора
     * @return представление подзадачи
     */
    static TaskUnitView<Object> unit(final ClaimedAttempt attempt, final ExecutionRef execution, final Object payload) {
        final ClaimedEntity entity = attempt.entity();
        return new TaskUnitView<>(
                attempt.target().id(),
                entity.parent().taskId(),
                entity.parent().batchId(),
                execution,
                attempt.taskType(),
                payload,
                entity.priority(),
                entity.createdAt());
    }
}
