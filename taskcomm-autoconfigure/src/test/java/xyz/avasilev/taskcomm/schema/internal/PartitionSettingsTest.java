package xyz.avasilev.taskcomm.schema.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/** Проверки свойств партиций, валящие старт (docs/rules/configuration.md, docs/adr/0080). */
class PartitionSettingsTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration HALF_YEAR = Duration.ofDays(180);

    @Test
    void acceptsDefaults() {
        assertThatCode(() -> new PartitionSettings(true, RangeCalendar.MONTH, 3, HOUR, false, HALF_YEAR))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCreateAheadBelowOne() {
        assertThatThrownBy(() -> new PartitionSettings(true, RangeCalendar.MONTH, 0, HOUR, false, HALF_YEAR))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("create-ahead");
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThatThrownBy(() -> new PartitionSettings(true, RangeCalendar.MONTH, 3, Duration.ZERO, false, HALF_YEAR))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("interval");
    }

    @Test
    void rejectsNonPositiveRetention() {
        assertThatThrownBy(() -> new PartitionSettings(true, RangeCalendar.MONTH, 3, HOUR, true, Duration.ZERO))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("retention");
    }

    @Test
    void rejectsDropWithoutManagement() {
        assertThatThrownBy(() -> new PartitionSettings(false, RangeCalendar.MONTH, 3, HOUR, true, HALF_YEAR))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("drop.enabled");
    }
}
