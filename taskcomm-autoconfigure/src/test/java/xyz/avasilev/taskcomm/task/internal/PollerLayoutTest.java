package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/** Раскладка поллеров: основной не берёт типов выделенных, тип — ровно у одного (docs/adr/0063). */
class PollerLayoutTest {

    private static final PollerSettings MAIN = poller(PollerSettings.MAIN, Set.of(), Duration.ofMinutes(1));

    private static final PollerSettings URGENT = poller("urgent", Set.of("order-urgent"), Duration.ofSeconds(5));

    @Test
    void mainPollerExcludesDedicatedTypes() {
        final PollerLayout layout = new PollerLayout(MAIN, List.of(URGENT));
        final Set<String> known = Set.of("order-urgent", "report", "orphan-free");

        assertThat(layout.claimableTypes(MAIN, known)).containsExactlyInAnyOrder("report", "orphan-free");
        assertThat(layout.claimableTypes(URGENT, known)).containsExactly("order-urgent");
    }

    @Test
    void deadlineStepComesFromPollerOfType() {
        final PollerLayout layout = new PollerLayout(MAIN, List.of(URGENT));

        assertThat(layout.deadlineStep("order-urgent")).isEqualTo(Duration.ofSeconds(5));
        assertThat(layout.deadlineStep("report")).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void typeNamedInTwoPollersFailsStartup() {
        final PollerSettings other = poller("other", Set.of("order-urgent"), Duration.ofMinutes(1));

        assertThatThrownBy(() -> new PollerLayout(MAIN, List.of(URGENT, other)))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("order-urgent")
                .hasMessageContaining("urgent")
                .hasMessageContaining("other");
    }

    @Test
    void dedicatedPollerNeedsTypesAndMainHasNone() {
        assertThatThrownBy(() -> poller("empty", Set.of(), Duration.ofMinutes(1)))
                .isInstanceOf(TaskcommConfigurationException.class);
        assertThatThrownBy(() -> poller(PollerSettings.MAIN, Set.of("report"), Duration.ofMinutes(1)))
                .isInstanceOf(TaskcommConfigurationException.class);
    }

    @Test
    void fifoShareLiesStrictlyBetweenZeroAndOne() {
        for (final double share : new double[] {0, 1, -0.1}) {
            assertThatThrownBy(() -> new PollerSettings(
                            PollerSettings.MAIN,
                            Set.of(),
                            Duration.ofSeconds(1),
                            100,
                            8,
                            ClaimForm.QUOTA,
                            Duration.ofMinutes(1),
                            share))
                    .isInstanceOf(TaskcommConfigurationException.class)
                    .hasMessageContaining("fifo-share");
        }
    }

    private static PollerSettings poller(final String name, final Set<String> types, final Duration deadlineStep) {
        return new PollerSettings(name, types, Duration.ofSeconds(1), 100, 8, ClaimForm.DEADLINE, deadlineStep, 0.1);
    }
}
