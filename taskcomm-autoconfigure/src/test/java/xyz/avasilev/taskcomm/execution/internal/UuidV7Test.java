package xyz.avasilev.taskcomm.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** UUIDv7: версия, вариант, время и монотонность внутри миллисекунды (docs/rules/persistence.md). */
class UuidV7Test {

    @Test
    void carriesVersionVariantAndClockMillis() {
        final Instant now = Instant.parse("2026-10-24T10:15:30.123Z");
        final UUID id = new UuidV7(Clock.fixed(now, ZoneOffset.UTC)).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }

    @Test
    void isStrictlyIncreasingWithinOneMillisecond() {
        final UuidV7 ids = new UuidV7(Clock.fixed(Instant.parse("2026-10-24T10:15:30Z"), ZoneOffset.UTC));

        assertThat(generate(ids, 10_000))
                .isSortedAccordingTo(UuidV7Test::compareUnsigned)
                .doesNotHaveDuplicates();
    }

    @Test
    void staysIncreasingWhenClockGoesBack() {
        final AtomicLong millis =
                new AtomicLong(Instant.parse("2026-10-24T10:15:30Z").toEpochMilli());
        final UuidV7 ids = new UuidV7(new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        });
        final UUID before = ids.next();
        millis.addAndGet(-5_000);
        final UUID after = ids.next();

        assertThat(compareUnsigned(after, before)).isPositive();
    }

    private static List<UUID> generate(final UuidV7 ids, final int count) {
        final List<UUID> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(ids.next());
        }
        return result;
    }

    /** Порядок, в котором сравнивает {@code uuid} PostgreSQL, — побайтово без знака. */
    private static int compareUnsigned(final UUID left, final UUID right) {
        final int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
