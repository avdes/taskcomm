package xyz.avasilev.taskcomm.schema.internal;

/**
 * Первые части ключей advisory-блокировок кода схемы — двухчастная форма {@code (int4, int4)},
 * которая в PostgreSQL не пересекается с формой с одним {@code bigint}
 * (docs/adr/0078-partition-maintenance.md). Значения записаны в docs/rules/partitioning.md:
 * ключ границы обязаны повторять подсистемы, оживляющие партицию.
 */
final class AdvisoryLocks {

    /** Обслуживание партиций, вторая часть — {@link #MAINTENANCE_SUBKEY}. */
    static final int MAINTENANCE = 0x74630001;

    /** Вторая часть ключа обслуживания партиций — постоянная. */
    static final int MAINTENANCE_SUBKEY = 0;

    /** Граница активных партиций, вторая часть — {@link RangeCalendar#rangeKey} диапазона. */
    static final int HORIZON_RANGE = 0x74630002;

    private AdvisoryLocks() {}
}
