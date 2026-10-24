package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;

/**
 * Настройки аренды одного уровня разрешения — {@code yaml} или аннотации. Незаданное — {@code null}:
 * берётся с уровня слабее (docs/adr/0061-processor-configuration-annotations.md).
 *
 * @param duration длительность аренды
 * @param heartbeatInterval интервал heartbeat
 * @param maxExecutionTime предел обработки
 */
public record LeaseOverride(Duration duration, Duration heartbeatInterval, Duration maxExecutionTime) {

    /** Ничего не задано. */
    public static final LeaseOverride NONE = new LeaseOverride(null, null, null);
}
