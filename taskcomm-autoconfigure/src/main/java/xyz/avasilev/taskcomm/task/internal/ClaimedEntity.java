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
 * @param payload нагрузка в JSON
 */
record ClaimedEntity(UUID executionId, int priority, Instant createdAt, Origin origin, String payload) {}
