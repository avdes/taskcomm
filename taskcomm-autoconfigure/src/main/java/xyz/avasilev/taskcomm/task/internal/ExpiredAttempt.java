package xyz.avasilev.taskcomm.task.internal;

import java.util.UUID;

/**
 * Брошенная попытка — открытая, с истёкшей арендой; её находит вторая ветка запроса захвата.
 *
 * @param entity ключ строки сущности попытки
 * @param taskType тип задачи
 * @param phase фаза попытки
 * @param attemptId идентификатор попытки
 * @param lockedBy экземпляр, не продливший аренду
 * @param leaseRenewals сколько раз аренда продлевалась
 * @param parent родство ребёнка раскрытия или {@code null} у задачи
 */
record ExpiredAttempt(
        TaskEntityRef entity,
        String taskType,
        TaskPhase phase,
        UUID attemptId,
        String lockedBy,
        int leaseRenewals,
        ClaimedEntity.Parent parent) {}
