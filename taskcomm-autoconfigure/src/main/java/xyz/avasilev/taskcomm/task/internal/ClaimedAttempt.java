package xyz.avasilev.taskcomm.task.internal;

import java.util.UUID;

/**
 * Захваченная работа — открытая попытка под арендой этого экземпляра и сущность, над которой
 * она идёт.
 *
 * @param lease аренда попытки
 * @param target сущность попытки
 * @param executionId дело сущности
 * @param taskType тип задачи
 * @param phase фаза попытки
 * @param priority приоритет сущности
 * @param payload нагрузка сущности в JSON
 */
record ClaimedAttempt(
        AttemptLease lease,
        TaskEntityRef target,
        UUID executionId,
        String taskType,
        TaskPhase phase,
        int priority,
        String payload) {}
