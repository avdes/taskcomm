package xyz.avasilev.taskcomm.schema.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Границы диапазонов партиций: UTC, неделя с понедельника, имя по нижней границе (docs/adr/0080). */
class RangeCalendarTest {

    private static final Instant WEDNESDAY_EVENING = Instant.parse("2026-10-21T22:30:00Z");

    @Test
    void dayStartsAtUtcMidnight() {
        final Instant lower = RangeCalendar.DAY.floor(WEDNESDAY_EVENING);

        assertThat(lower).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
        assertThat(RangeCalendar.DAY.next(lower)).isEqualTo(Instant.parse("2026-10-22T00:00:00Z"));
    }

    @Test
    void weekStartsOnMonday() {
        final Instant lower = RangeCalendar.WEEK.floor(WEDNESDAY_EVENING);

        assertThat(lower).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
        assertThat(RangeCalendar.WEEK.next(lower)).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        assertThat(RangeCalendar.WEEK.floor(lower)).isEqualTo(lower);
    }

    @Test
    void monthCrossesYearBoundary() {
        final Instant december = RangeCalendar.MONTH.floor(Instant.parse("2026-12-31T23:59:59Z"));

        assertThat(december).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(RangeCalendar.MONTH.next(december)).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void alignmentDependsOnPeriod() {
        final Instant monday = Instant.parse("2026-10-19T00:00:00Z");

        assertThat(RangeCalendar.WEEK.isAligned(monday)).isTrue();
        assertThat(RangeCalendar.MONTH.isAligned(monday)).isFalse();
        assertThat(RangeCalendar.DAY.isAligned(WEDNESDAY_EVENING)).isFalse();
    }

    @Test
    void partitionNameUsesLowerBoundAndFitsPostgresLimit() {
        final String name =
                RangeCalendar.partitionName("tc_communication_attempt", Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(name).isEqualTo("tc_communication_attempt_p20261001");
        assertThat(name.length()).isLessThanOrEqualTo(63);
    }

    @Test
    void rangeKeyCountsDaysFromEpoch() {
        assertThat(RangeCalendar.rangeKey(Instant.parse("1970-01-02T00:00:00Z")))
                .isEqualTo(1);
        assertThat(RangeCalendar.rangeKey(Instant.parse("2026-10-01T00:00:00Z")))
                .isEqualTo(20_727);
    }
}
