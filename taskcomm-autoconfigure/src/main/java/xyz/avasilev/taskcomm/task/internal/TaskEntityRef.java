package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Ключ строки сущности задач: уровень выбирает таблицу, ключ партиционирования и идентификатор —
 * строку в ней с отсечением партиций.
 *
 * @param level уровень сущности
 * @param id идентификатор сущности
 * @param executionCreatedAt ключ партиционирования — время создания дела
 */
record TaskEntityRef(TaskLevel level, UUID id, Instant executionCreatedAt) {}
