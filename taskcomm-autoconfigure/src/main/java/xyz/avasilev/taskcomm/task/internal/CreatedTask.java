package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Только что созданная задача — то, что вставка узнала от базы.
 *
 * @param id идентификатор задачи
 * @param execution дело задачи
 * @param createdAt время создания по часам базы
 */
record CreatedTask(UUID id, ExecutionRef execution, Instant createdAt) {}
