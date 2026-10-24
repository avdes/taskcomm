package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Задача к созданию — всё уже разрешено: тип, нагрузка в JSON, приоритет, шаг дедлайна поллера
 * этого типа, дело.
 *
 * @param taskType тип задачи
 * @param payload нагрузка, сериализованная в JSON
 * @param priority приоритет, больше нуля; меньше значит важнее
 * @param deadlineStep шаг дедлайна поллера, захватывающего этот тип
 * @param notBefore не раньше какого момента захватывать; {@code null} — сразу
 * @param execution дело; {@code null} — завести новое
 * @param origin создатель внутри того же дела; {@code null} — сущность корневая
 */
record NewTask(
        String taskType,
        String payload,
        int priority,
        Duration deadlineStep,
        Instant notBefore,
        ExecutionRef execution,
        Origin origin) {

    /**
     * Проверяет обязательные поля и связь с делом.
     *
     * @throws IllegalArgumentException если приоритет не положительный или создатель указан без дела
     */
    NewTask {
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(deadlineStep, "deadlineStep");
        if (priority <= 0) {
            throw new IllegalArgumentException("Приоритет должен быть больше нуля, а передан " + priority);
        }
        if (origin != null && execution == null) {
            throw new IllegalArgumentException("Создатель лежит в том же деле, что и сущность: без дела его нет");
        }
    }
}
