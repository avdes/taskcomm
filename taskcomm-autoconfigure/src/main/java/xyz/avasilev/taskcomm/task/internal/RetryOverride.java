package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;

/**
 * Настройки повторов одного уровня разрешения — {@code yaml} или аннотации. Незаданное — {@code null}:
 * берётся с уровня слабее (docs/adr/0061-processor-configuration-annotations.md).
 *
 * @param maxAttempts лимит попыток
 * @param initialDelay первая задержка backoff
 * @param multiplier множитель backoff
 * @param maxDelay верхняя граница задержки
 * @param jitter доля джиттера
 */
public record RetryOverride(
        Integer maxAttempts, Duration initialDelay, Double multiplier, Duration maxDelay, Double jitter) {

    /** Ничего не задано. */
    public static final RetryOverride NONE = new RetryOverride(null, null, null, null, null);
}
