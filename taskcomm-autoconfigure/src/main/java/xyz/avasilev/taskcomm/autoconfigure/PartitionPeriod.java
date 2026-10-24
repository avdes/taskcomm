package xyz.avasilev.taskcomm.autoconfigure;

/**
 * Размер партиции — {@code taskcomm.schema.partitions.period}
 * (docs/adr/0080-partition-configuration.md).
 *
 * <p>Границы считаются в UTC. Размер выбирается один раз: если границы партиций в базе
 * не совпадают с выбранным размером, приложение не стартует.
 */
public enum PartitionPeriod {

    /** Сутки. */
    DAY,

    /** Неделя, с понедельника по ISO 8601. */
    WEEK,

    /** Календарный месяц. */
    MONTH
}
