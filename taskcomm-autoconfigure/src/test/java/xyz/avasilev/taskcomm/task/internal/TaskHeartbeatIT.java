package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
    void renewsEachAttemptByIntervalOfItsMethod() {
        final AtomicLong nanos = new AtomicLong();
        final TaskHeartbeat scheduled = new TaskHeartbeat(this.db.dataSource, this.db.transactionManager, nanos::get);
        this.db.submit("fast", 100);
        this.db.submit("slow", 100);
        final AttemptSettings fast = new AttemptSettings(
                new RetryPolicy(3, Duration.ZERO, 2.0, Duration.ofHours(1), 0.2),
                new LeaseSettings(Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMinutes(1)));
        final AttemptSettings slow = new AttemptSettings(
                fast.retry(), new LeaseSettings(Duration.ofSeconds(12), Duration.ofSeconds(4), Duration.ofMinutes(1)));
        new TaskClaimer(
                        this.db.dataSource,
                        this.db.transactionManager,
                        this.db.ids,
                        (type, method) -> type.equals("fast") ? fast : slow,
                        TaskTestSettings.layout(),
                        () -> 0.5,
                        "worker-a")
                .claim(
                        TaskTestSettings.mainPoller(ClaimForm.DEADLINE),
                        Map.of("fast", ContractShape.FLAT, "slow", ContractShape.FLAT),
                        2)
                .forEach(scheduled::track);
        final Duration tick = Duration.ofSeconds(1);

        nanos.set(Duration.ofMillis(500).toNanos());
        scheduled.renewDue(tick);
        nanos.set(Duration.ofMillis(3_500).toNanos());
        scheduled.renewDue(tick);

        assertThat(this.renewals("fast")).isEqualTo(2);
        assertThat(this.renewals("slow")).isEqualTo(1);
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

    private int renewals(final String taskType) {
        return this.db
                .jdbc
                .sql("SELECT a.lease_renewals FROM tc_task_attempt a JOIN tc_task t ON t.id = a.target_id"
                        + " WHERE t.task_type = :type")
                .param("type", taskType)
                .query(Integer.class)
                .single();
    }

    private String entities() {
        return this.db
                .jdbc
                .sql("SELECT string_agg(id || status || updated_at, ',' ORDER BY id) FROM tc_task")
                .query(String.class)
                .single();
    }
}
