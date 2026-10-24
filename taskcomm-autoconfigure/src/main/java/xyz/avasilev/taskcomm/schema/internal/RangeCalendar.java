package xyz.avasilev.taskcomm.schema.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Календарь диапазонов партиций: границы в UTC, неделя с понедельника
 * (docs/adr/0080-partition-configuration.md). Значения совпадают по имени
 * с {@code PartitionPeriod} из свойств.
 */
public enum RangeCalendar {

    /** Сутки. */
    DAY {
        @Override
        LocalDate floorDate(final LocalDate date) {
            return date;
        }

        @Override
        LocalDate nextDate(final LocalDate lower) {
            return lower.plusDays(1);
        }
    },

    /** Неделя с понедельника. */
    WEEK {
        @Override
        LocalDate floorDate(final LocalDate date) {
            return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        @Override
        LocalDate nextDate(final LocalDate lower) {
            return lower.plusWeeks(1);
        }
    },

    /** Календарный месяц. */
    MONTH {
        @Override
        LocalDate floorDate(final LocalDate date) {
            return date.withDayOfMonth(1);
        }

        @Override
        LocalDate nextDate(final LocalDate lower) {
            return lower.plusMonths(1);
        }
    };

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Нижняя граница диапазона, которому принадлежит момент.
     *
     * @param moment момент времени
     * @return начало диапазона в UTC
     */
    public Instant floor(final Instant moment) {
        return this.floorDate(moment.atZone(ZoneOffset.UTC).toLocalDate())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /**
     * Нижняя граница следующего диапазона.
     *
     * @param lower нижняя граница диапазона, выровненная по календарю
     * @return нижняя граница следующего диапазона, она же верхняя граница данного
     */
    public Instant next(final Instant lower) {
        return this.nextDate(lower.atZone(ZoneOffset.UTC).toLocalDate())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /**
     * Выровнена ли граница по календарю.
     *
     * @param moment проверяемая граница
     * @return {@code true}, если это начало диапазона
     */
    public boolean isAligned(final Instant moment) {
        return this.floor(moment).equals(moment);
    }

    /**
     * Имя партиции диапазона — {@code <родитель>_p<ГГГГММДД нижней границы>}.
     *
     * @param parent имя родительской таблицы
     * @param lower нижняя граница диапазона
     * @return имя партиции
     */
    public static String partitionName(final String parent, final Instant lower) {
        return parent + "_p" + SUFFIX.format(lower.atZone(ZoneOffset.UTC));
    }

    /**
     * Ключ диапазона для advisory-блокировки — число суток от эпохи до нижней границы. Один
     * и тот же у такта обслуживания и у оживления партиции в подсистемах.
     *
     * @param lower нижняя граница диапазона
     * @return ключ, умещающийся в {@code int4}
     */
    public static int rangeKey(final Instant lower) {
        return Math.toIntExact(ChronoUnit.DAYS.between(Instant.EPOCH, lower));
    }

    abstract LocalDate floorDate(LocalDate date);

    abstract LocalDate nextDate(LocalDate lower);
}
