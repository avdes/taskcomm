package xyz.avasilev.taskcomm.schema.internal;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Итоговые настройки партиций — {@code taskcomm.schema.partitions.*}. Недопустимое сочетание
 * не даёт собрать значение: проверки «падение при старте» из docs/rules/configuration.md,
 * касающиеся одних только свойств, живут здесь.
 *
 * @param manage ведёт ли партиции стартер
 * @param calendar размер партиции
 * @param createAhead сколько периодов вперёд держать созданными
 * @param interval такт обслуживания
 * @param dropEnabled удалять ли старые партиции
 * @param retention глубина хранения, от верхней границы партиции
 */
public record PartitionSettings(
        boolean manage,
        RangeCalendar calendar,
        int createAhead,
        Duration interval,
        boolean dropEnabled,
        Duration retention) {

    /**
     * Проверяет сочетание свойств.
     *
     * @throws TaskcommConfigurationException если с такими настройками стартовать нельзя
     */
    public PartitionSettings {
        if (createAhead < 1) {
            throw new TaskcommConfigurationException("taskcomm.schema.partitions.create-ahead = " + createAhead
                    + ", а должно быть не меньше 1: партиции следующего периода должны существовать заранее.");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new TaskcommConfigurationException(
                    "taskcomm.schema.partitions.interval = " + interval + ", а должен быть положительным.");
        }
        if (retention.isNegative() || retention.isZero()) {
            throw new TaskcommConfigurationException(
                    "taskcomm.schema.partitions.drop.retention = " + retention + ", а должен быть положительным.");
        }
        if (dropEnabled && !manage) {
            throw new TaskcommConfigurationException(
                    "taskcomm.schema.partitions.drop.enabled = true при manage = false: стартер удалял бы партиции, "
                            + "которые не создаёт. Удаляйте их своим инструментом или включите manage.");
        }
    }
}
