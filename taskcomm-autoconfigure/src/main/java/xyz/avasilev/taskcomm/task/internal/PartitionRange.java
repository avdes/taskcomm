package xyz.avasilev.taskcomm.task.internal;

/**
 * Размер диапазона партиций — тот же {@code taskcomm.schema.partitions.period}, что ведёт код
 * схемы, в форме единицы {@code date_trunc}. Своя копия, а не общий тип со схемой: общего
 * Java-кода у подсистем со схемой нет (docs/adr/0107-schema-internal-package.md), а нижняя граница
 * диапазона нужна оживлению, чтобы повторить ключ блокировки в точности.
 */
public enum PartitionRange {

    /** Сутки. */
    DAY("day"),

    /** Неделя с понедельника — {@code date_trunc('week', ...)} считает её так же, как ISO 8601. */
    WEEK("week"),

    /** Календарный месяц. */
    MONTH("month");

    private final String truncUnit;

    PartitionRange(final String truncUnit) {
        this.truncUnit = truncUnit;
    }

    /**
     * Единица {@code date_trunc}.
     *
     * @return {@code day}, {@code week} или {@code month}
     */
    String truncUnit() {
        return this.truncUnit;
    }
}
