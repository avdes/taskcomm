package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.task.api.TaskStatus;

/**
 * Всё о задаче, кроме нагрузки, — снимок строки {@code tc_task}.
 *
 * @param id идентификатор
 * @param executionRef дело
 * @param taskType тип задачи
 * @param priority приоритет
 * @param status статус на момент чтения
 * @param statusDescription причина терминального статуса или {@code null}
 * @param createdAt время создания
 * @param origin указатель на создателя или {@code null}
 */
record TaskRow(
        UUID id,
        ExecutionRef executionRef,
        String taskType,
        int priority,
        TaskStatus status,
        String statusDescription,
        Instant createdAt,
        Origin origin) {}
