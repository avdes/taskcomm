package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Снимок захваченной сущности — то, из чего воркер собирает её публичное представление.
 *
 * @param executionId дело сущности
 * @param priority приоритет
 * @param createdAt время создания сущности
 * @param origin указатель на создателя; бывает только у задачи
 * @param parent родство группы или подзадачи; у задачи {@code null}
 * @param payload нагрузка в JSON
 */
record ClaimedEntity(UUID executionId, int priority, Instant createdAt, Origin origin, Parent parent, String payload) {

    /**
     * Родство ребёнка раскрытия (docs/adr/0047-children-belong-to-attempt.md).
     *
     * @param taskId задача
     * @param batchId группа подзадачи или {@code null} у группы и у подзадачи без групп
     * @param attemptId попытка раскрытия, породившая ребёнка
     */
    record Parent(UUID taskId, UUID batchId, UUID attemptId) {

        /**
         * Сущность-родитель: группа у подзадачи группы, иначе задача.
         *
         * @param executionCreatedAt ключ партиционирования — общий у всей иерархии
         * @return ключ строки родителя
         */
        TaskEntityRef entity(final Instant executionCreatedAt) {
            return this.batchId == null
                    ? new TaskEntityRef(TaskLevel.TASK, this.taskId, executionCreatedAt)
                    : new TaskEntityRef(TaskLevel.BATCH, this.batchId, executionCreatedAt);
        }
    }
}
