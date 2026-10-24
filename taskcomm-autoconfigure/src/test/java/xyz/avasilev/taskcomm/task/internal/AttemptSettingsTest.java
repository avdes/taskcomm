package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/** Политика повторов и аренда: проверки «падение при старте» и расчёт (docs/rules/configuration.md). */
class AttemptSettingsTest {

    private static final RetryPolicy RETRY =
            new RetryPolicy(3, Duration.ofSeconds(5), 2.0, Duration.ofSeconds(30), 0.2);

    @Test
    void delayGrowsExponentiallyUpToCeiling() {
        assertThat(RETRY.delay(1, () -> 0.5)).isEqualTo(Duration.ofSeconds(5));
        assertThat(RETRY.delay(2, () -> 0.5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(RETRY.delay(3, () -> 0.5)).isEqualTo(Duration.ofSeconds(20));
        assertThat(RETRY.delay(10, () -> 0.5)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void jitterSpreadsDelayBothWays() {
        assertThat(RETRY.delay(1, () -> 0.0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(RETRY.delay(1, () -> 0.999_999)).isBetween(Duration.ofMillis(5_999), Duration.ofSeconds(6));
    }

    @Test
    void limitCountsAttemptsInCycle() {
        assertThat(RETRY.exhausted(2)).isFalse();
        assertThat(RETRY.exhausted(3)).isTrue();
    }

    @Test
    void retryRejectsValuesSystemCannotWorkWith() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, 1, Duration.ZERO, 0.2))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMinutes(2), 2, Duration.ofMinutes(1), 0.2))
                .hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, 0.5, Duration.ofMinutes(1), 0.2))
                .hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, 2, Duration.ofMinutes(1), 0))
                .hasMessageContaining("jitter");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, 2, Duration.ofMinutes(1), 1.5))
                .hasMessageContaining("jitter");
    }

    @Test
    void maxExecutionTimeBecomesRenewalCount() {
        assertThat(new LeaseSettings(Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofMinutes(30))
                        .maxRenewals())
                .isEqualTo(90);
    }

    @Test
    void leaseFailsWhenHeartbeatIsNotShorterThanLease() {
        assertThatThrownBy(
                        () -> new LeaseSettings(Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofMinutes(1)))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("heartbeat-interval")
                .hasMessageContaining("PT6.666");
    }

    @Test
    void leaseFailsWhenMaxExecutionTimeIsShorterThanHeartbeat() {
        assertThatThrownBy(
                        () -> new LeaseSettings(Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(10)))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("max-execution-time");
    }
}
