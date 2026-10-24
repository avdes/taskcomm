package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Аренда одной фазы одного типа задачи: длительность, интервал heartbeat и предел обработки
 * (docs/adr/0025-lease-mechanics.md, docs/adr/0060-retry-and-lease-configuration.md).
 * Недопустимое сочетание не даёт собрать значение — проверки «падение при старте»
 * из docs/rules/configuration.md; предупреждения печатает тот, кто разрешает настройки.
 *
 * @param duration на сколько выдаётся и продлевается аренда
 * @param heartbeatInterval такт продления, строго меньше аренды
 * @param maxExecutionTime предел обработки, не меньше такта продления
 */
public record LeaseSettings(Duration duration, Duration heartbeatInterval, Duration maxExecutionTime) {

    /**
     * Проверяет сочетание.
     *
     * @throws TaskcommConfigurationException если с такими значениями стартовать нельзя
     */
    public LeaseSettings {
        if (!positive(duration) || !positive(heartbeatInterval) || !positive(maxExecutionTime)) {
            throw new TaskcommConfigurationException("lease: duration = " + duration + ", heartbeat-interval = "
                    + heartbeatInterval + ", max-execution-time = " + maxExecutionTime
                    + " — все три должны быть положительными.");
        }
        if (heartbeatInterval.compareTo(duration) >= 0) {
            throw new TaskcommConfigurationException("lease: heartbeat-interval = " + heartbeatInterval
                    + " не меньше duration = " + duration + ": аренда истекала бы раньше первого продления. "
                    + "Рекомендуемый интервал — " + duration.dividedBy(3)
                    + " (docs/adr/0103-heartbeat-not-shorter-than-lease-fails.md).");
        }
        if (maxExecutionTime.compareTo(heartbeatInterval) < 0) {
            throw new TaskcommConfigurationException("lease: max-execution-time = " + maxExecutionTime
                    + " меньше heartbeat-interval = " + heartbeatInterval + ".");
        }
    }

    /**
     * Предел обработки, переведённый в счётчик продлений: дошли — продление прекращается,
     * аренда истекает и попытку закрывает переподхват. Точность — до такта heartbeat.
     *
     * @return сколько раз можно продлить аренду одной попытки
     */
    public int maxRenewals() {
        return Math.toIntExact(this.maxExecutionTime.dividedBy(this.heartbeatInterval));
    }

    private static boolean positive(final Duration value) {
        return !value.isNegative() && !value.isZero();
    }
}
