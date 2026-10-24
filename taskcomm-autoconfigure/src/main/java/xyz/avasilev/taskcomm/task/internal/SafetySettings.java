package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Настройки подстраховочного поллера — {@code taskcomm.task.safety} (docs/adr/0126-safety-poller.md).
 *
 * @param interval такт
 * @param grace сколько шаг каскада может не доводиться, прежде чем подстраховка его доведёт
 */
public record SafetySettings(Duration interval, Duration grace) {

    /**
     * Проверяет значения.
     *
     * @param interval такт
     * @param grace выдержка
     * @throws TaskcommConfigurationException если значение не задано или не положительно
     */
    public SafetySettings {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new TaskcommConfigurationException("interval должен быть больше нуля: " + interval);
        }
        if (grace == null || grace.isNegative() || grace.isZero()) {
            throw new TaskcommConfigurationException("grace должен быть больше нуля: " + grace);
        }
    }
}
