package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Heartbeat: продлевает аренды экземпляра одним запросом, трогает только строки попыток,
 * останавливается на пределе обработки (docs/adr/0025-lease-mechanics.md).
 */
class TaskHeartbeatIT {

    private static final Map<String, ContractShape> FLAT = Map.of(TaskTestDatabase.FLAT_TYPE, ContractShape.FLAT);

    private TaskTestDatabase db;

    private TaskHeartbeat heartbeat;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
        this.heartbeat = new TaskHeartbeat(this.db.dataSource, this.db.transactionManager);
    }

    @Test
    void renewsAllLeasesOfInstanceWithoutTouchingEntities() {
        this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));
        this.db
                .claimer("worker-a", settings, () -> 0.5)
                .claim(TaskTestSettings.mainPoller(ClaimForm.DEADLINE), FLAT, 2)
                .forEach(this.heartbeat::track);
        this.db
                .jdbc
                .sql("UPDATE tc_task_attempt SET locked_until = now() + interval '1 second'")
                .update();
        final String entitiesBefore = this.entities();

        assertThat(this.heartbeat.renew()).isEmpty();

        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task_attempt WHERE lease_renewals = 1"
                                + " AND locked_until > now() + interval '50 seconds' AND updated_at = created_at",
                        null))
                .isEqualTo(2);
        assertThat(this.entities()).isEqualTo(entitiesBefore);
    }

    @Test
    void stopsRenewingAtMaxExecutionTime() {
        this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));
        final ClaimedAttempt attempt = this.db
                .claimer("worker-a", settings, () -> 0.5)
                .claim(TaskTestSettings.mainPoller(ClaimForm.DEADLINE), FLAT, 1)
                .getFirst();
        this.heartbeat.track(attempt);

        for (int i = 0; i < attempt.lease().maxRenewals(); i++) {
            assertThat(this.heartbeat.renew()).isEmpty();
        }
        assertThat(this.heartbeat.isLost(attempt)).isFalse();

        assertThat(this.heartbeat.renew()).containsExactly(attempt.lease().attemptId());
        assertThat(this.heartbeat.isLost(attempt)).isTrue();
        assertThat(this.heartbeat.renew()).isEmpty();
    }

    @Test
    void attemptClosedByReclaimIsLost() throws Exception {
        this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ofMinutes(1), Duration.ofSeconds(1));
        final TaskClaimer claimer = this.db.claimer("worker-a", settings, () -> 0.5);
        final ClaimedAttempt attempt = claimer.claim(TaskTestSettings.mainPoller(ClaimForm.DEADLINE), FLAT, 1)
                .getFirst();
        this.heartbeat.track(attempt);
        Thread.sleep(1_200);
        claimer.claim(TaskTestSettings.mainPoller(ClaimForm.DEADLINE), FLAT, 1);

        assertThat(this.heartbeat.renew()).containsExactly(attempt.lease().attemptId());
        assertThat(this.heartbeat.isLost(attempt)).isTrue();
    }

    private String entities() {
        return this.db
                .jdbc
                .sql("SELECT string_agg(id || status || updated_at, ',' ORDER BY id) FROM tc_task")
                .query(String.class)
                .single();
    }
}
