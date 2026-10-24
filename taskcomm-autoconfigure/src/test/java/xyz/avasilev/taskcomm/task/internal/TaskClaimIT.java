package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Захват по аренде на настоящем PostgreSQL (docs/adr/0046-lease-in-attempt.md,
 * docs/adr/0067-expired-lease-closes-attempt.md, docs/adr/0063-claim-ordering-and-pollers.md).
 *
 * <p>Критерий готовности этапа 3: два воркера не берут одну строку, а падение одного закрывает его
 * попытку исходом {@code LEASE_LOST} и возвращает работу в очередь.
 */
class TaskClaimIT {

    private static final Map<String, ContractShape> FLAT = Map.of(TaskTestDatabase.FLAT_TYPE, ContractShape.FLAT);

    private static final PollerSettings DEADLINE = TaskTestSettings.mainPoller(ClaimForm.DEADLINE);

    private TaskTestDatabase db;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
    }

    @Test
    void twoWorkersNeverClaimTheSameRow() throws Exception {
        final int tasks = 60;
        for (int i = 0; i < tasks; i++) {
            this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        }
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));
        final List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            final List<Future<Integer>> results = new ArrayList<>();
            for (final String instance : List.of("worker-a", "worker-b")) {
                final TaskClaimer claimer = this.db.claimer(instance, settings, () -> 0.5);
                results.add(workers.submit(drain(claimer, claimed, start)));
            }
            start.countDown();
            final int byA = results.get(0).get();
            final int byB = results.get(1).get();
            assertThat(byA + byB).isEqualTo(tasks);
        } finally {
            workers.shutdownNow();
        }

        assertThat(claimed).hasSize(tasks).doesNotHaveDuplicates();
        assertThat(this.db.count("SELECT count(*) FROM tc_task_attempt WHERE finished_at IS NULL", null))
                .isEqualTo(tasks);
        assertThat(this.db.count(
                        "SELECT count(*) FROM (SELECT target_id FROM tc_task_attempt GROUP BY target_id"
                                + " HAVING count(*) > 1) twice",
                        null))
                .isZero();
        assertThat(this.db.count("SELECT count(*) FROM tc_task WHERE status <> 'RUNNING'", null))
                .isZero();
    }

    @Test
    void crashedWorkerLosesLeaseAndWorkReturnsToQueue() throws Exception {
        final CreatedTask task = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofSeconds(1));
        final TaskClaimer crashing = this.db.claimer("worker-a", settings, () -> 0.5);
        final TaskClaimer survivor = this.db.claimer("worker-b", settings, () -> 0.5);

        final ClaimedAttempt lost = crashing.claim(DEADLINE, FLAT, 1).getFirst();
        assertThat(survivor.claim(DEADLINE, FLAT, 1)).isEmpty();
        Thread.sleep(1_200);

        assertThat(survivor.claim(DEADLINE, FLAT, 1)).isEmpty();
        final Map<String, Object> closed = this.attempt(lost.lease().attemptId());
        assertThat(closed.get("status")).isEqualTo("LEASE_LOST");
        assertThat(closed.get("status_description")).isEqualTo("аренда истекла: экземпляр worker-a не продлил её");
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");

        final ClaimedAttempt again = survivor.claim(DEADLINE, FLAT, 1).getFirst();
        assertThat(again.target().id()).isEqualTo(task.id());
        assertThat(this.attempt(again.lease().attemptId()).get("locked_by")).isEqualTo("worker-b");
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("RUNNING");
    }

    @Test
    void lateWorkerChangesNeitherEntityNorResult() throws Exception {
        final CreatedTask task = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofSeconds(1));
        final TaskClaimer claimer = this.db.claimer("worker-a", settings, () -> 0.5);
        final TaskAttemptCloser closer = this.db.closer(settings);
        final ClaimedAttempt late = claimer.claim(DEADLINE, FLAT, 1).getFirst();
        Thread.sleep(1_200);
        claimer.claim(DEADLINE, FLAT, 1);
        final ClaimedAttempt current = claimer.claim(DEADLINE, FLAT, 1).getFirst();

        assertThat(closer.succeed(late, "{\"late\":true}")).isFalse();
        assertThat(closer.fail(late, "упал", "битая нагрузка")).isFalse();

        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("RUNNING");
        assertThat(this.attempt(late.lease().attemptId()).get("status")).isEqualTo("LEASE_LOST");
        assertThat(this.attempt(current.lease().attemptId()).get("finished_at")).isNull();
        assertThat(this.db.count("SELECT count(*) FROM tc_task_result WHERE target_id = :id", task.id()))
                .isZero();

        assertThat(closer.succeed(current, "{\"done\":true}")).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("SUCCEEDED");
        assertThat(this.db
                        .jdbc
                        .sql("SELECT result::text FROM tc_task_result WHERE attempt_id = :id")
                        .param("id", current.lease().attemptId())
                        .query(String.class)
                        .single())
                .isEqualTo("{\"done\": true}");
    }

    @Test
    void lostLeaseBacksOffAndRecalculatesClaimOrder() throws Exception {
        final CreatedTask task = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ofSeconds(10), Duration.ofSeconds(1));
        final TaskClaimer claimer = this.db.claimer("worker-a", settings, () -> 0.5);
        claimer.claim(DEADLINE, FLAT, 1);
        Thread.sleep(1_200);

        claimer.claim(DEADLINE, FLAT, 1);

        final Map<String, Object> row =
                this.db.jdbc.sql("""
                        SELECT status, extract(epoch FROM next_attempt_at - now()) AS delay,
                               extract(epoch FROM claim_order_at - next_attempt_at) AS offset_seconds
                          FROM tc_task WHERE id = :id
                        """).param("id", task.id()).query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("delay")).doubleValue()).isBetween(9.0, 10.5);
        assertThat(((Number) row.get("offset_seconds")).doubleValue()).isEqualTo(100 * 60.0);
    }

    @Test
    void exhaustedAttemptsMakeEntityFailed() throws Exception {
        final CreatedTask task = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(1, Duration.ZERO, Duration.ofSeconds(1));
        final TaskClaimer claimer = this.db.claimer("worker-a", settings, () -> 0.5);
        claimer.claim(DEADLINE, FLAT, 1);
        Thread.sleep(1_200);

        assertThat(claimer.claim(DEADLINE, FLAT, 1)).isEmpty();

        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("FAILED");
        assertThat(this.db
                        .jdbc
                        .sql("SELECT status_description FROM tc_task WHERE id = :id")
                        .param("id", task.id())
                        .query(String.class)
                        .single())
                .isEqualTo(TaskRetries.EXHAUSTED);
    }

    @Test
    void attemptLimitCountsOnlyAttemptsOfTheSamePhase() throws Exception {
        final CreatedTask task = this.db.submit("order-sync", 100);
        this.db.jdbc.sql("""
                        UPDATE tc_task SET contract_shape = 'UNIT', phase = 'COLLECT' WHERE id = :id;
                        INSERT INTO tc_task_attempt (id, created_at, updated_at, execution_created_at, level, target_id,
                                                     phase, locked_by, locked_until, lease_token, started_at,
                                                     finished_at, status)
                        SELECT gen_random_uuid(), now(), now(), execution_created_at, 'TASK', id, 'EXPAND',
                               'worker-a', now(), gen_random_uuid(), now(), now(), 'SUCCEEDED'
                          FROM tc_task WHERE id = :id
                        """).param("id", task.id()).update();
        final AttemptSettings settings = TaskTestSettings.settings(2, Duration.ZERO, Duration.ofSeconds(1));
        final TaskClaimer claimer = this.db.claimer("worker-a", settings, () -> 0.5);
        final Map<String, ContractShape> unit = Map.of("order-sync", ContractShape.UNIT);
        assertThat(claimer.claim(DEADLINE, unit, 1).getFirst().phase()).isEqualTo(TaskPhase.COLLECT);
        Thread.sleep(1_200);

        claimer.claim(DEADLINE, unit, 1);

        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");
    }

    @Test
    void failedAttemptRetriesOrFailsWhenRetryIsForbidden() {
        final CreatedTask retried = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final CreatedTask forbidden = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ofSeconds(30), Duration.ofMinutes(1));
        final TaskAttemptCloser closer = this.db.closer(settings);
        final List<ClaimedAttempt> claimed =
                this.db.claimer("worker-a", settings, () -> 0.5).claim(DEADLINE, FLAT, 2);

        for (final ClaimedAttempt attempt : claimed) {
            final String doNotRetry = attempt.target().id().equals(retried.id()) ? null : "битая нагрузка";
            assertThat(closer.fail(attempt, "внешняя система недоступна", doNotRetry))
                    .isTrue();
        }

        assertThat(this.db.status(TaskLevel.TASK, retried.id())).isEqualTo("PENDING");
        assertThat(this.db.status(TaskLevel.TASK, forbidden.id())).isEqualTo("FAILED");
        assertThat(this.db
                        .jdbc
                        .sql("SELECT status_description FROM tc_task WHERE id = :id")
                        .param("id", forbidden.id())
                        .query(String.class)
                        .single())
                .isEqualTo("повторять запрещено: битая нагрузка");
    }

    @Test
    void firstClaimWritesContractShapeAndPhase() {
        final CreatedTask task = this.db.submit("order-sync", 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

        final ClaimedAttempt attempt = this.db
                .claimer("worker-a", settings, () -> 0.5)
                .claim(DEADLINE, Map.of("order-sync", ContractShape.UNIT), 1)
                .getFirst();

        assertThat(attempt.phase()).isEqualTo(TaskPhase.EXPAND);
        assertThat(this.db
                        .jdbc
                        .sql("SELECT contract_shape || '/' || phase FROM tc_task WHERE id = :id")
                        .param("id", task.id())
                        .query(String.class)
                        .single())
                .isEqualTo("UNIT/EXPAND");
        assertThat(this.attempt(attempt.lease().attemptId()).get("phase")).isEqualTo("EXPAND");
    }

    @Test
    void taskStartedUnderAnotherShapeIsNotClaimed() {
        final CreatedTask task = this.db.submit("order-sync", 100);
        this.db
                .jdbc
                .sql("UPDATE tc_task SET contract_shape = 'FLAT', phase = 'PROCESS' WHERE id = :id")
                .param("id", task.id())
                .update();
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

        assertThat(this.db
                        .claimer("worker-a", settings, () -> 0.5)
                        .claim(DEADLINE, Map.of("order-sync", ContractShape.UNIT), 1))
                .isEmpty();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");
    }

    @Test
    void pollerClaimsOnlyItsTypes() {
        this.db.submit("noisy", 100);
        final CreatedTask mine = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

        final List<ClaimedAttempt> claimed =
                this.db.claimer("worker-a", settings, () -> 0.5).claim(DEADLINE, FLAT, 10);

        assertThat(claimed).extracting(attempt -> attempt.target().id()).containsExactly(mine.id());
    }

    @Test
    void unitsAreClaimedForProcessing() {
        final CreatedTask parent = this.db.submit("order-sync", 100);
        final UUID unit = this.db.ids.next();
        this.db
                .jdbc
                .sql("""
                        INSERT INTO tc_task_unit (id, created_at, updated_at, execution_id, execution_created_at,
                                                  task_id, parent_attempt_id, task_type, payload, status, priority,
                                                  next_attempt_at, claim_order_at, retry_cycle_started_at)
                        VALUES (:id, now(), now(), :executionId, :executionCreatedAt, :taskId, gen_random_uuid(),
                                'order-sync', '{"orderId":"A-1"}', 'PENDING', 100, now(), now(), now())
                        """)
                .param("id", unit)
                .param("executionId", parent.execution().id())
                .param(
                        "executionCreatedAt",
                        TaskSql.parameter(parent.execution().createdAt()))
                .param("taskId", parent.id())
                .update();
        this.db.jdbc.sql("UPDATE tc_task SET status = 'AWAITING_CHILDREN'").update();
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

        final ClaimedAttempt attempt = this.db
                .claimer("worker-a", settings, () -> 0.5)
                .claim(DEADLINE, Map.of("order-sync", ContractShape.UNIT), 1)
                .getFirst();

        assertThat(attempt.target().level()).isEqualTo(TaskLevel.UNIT);
        assertThat(attempt.target().id()).isEqualTo(unit);
        assertThat(attempt.phase()).isEqualTo(TaskPhase.PROCESS);
        assertThat(attempt.entity().payload()).contains("A-1");
        assertThat(this.db.status(TaskLevel.UNIT, unit)).isEqualTo("RUNNING");
        assertThat(this.db.closer(settings).succeed(attempt, null)).isTrue();
        assertThat(this.db.status(TaskLevel.UNIT, unit)).isEqualTo("SUCCEEDED");
        assertThat(this.db.count("SELECT count(*) FROM tc_task_result", null)).isZero();
    }

    @Test
    void claimFormsOrderReadyWorkDifferently() throws Exception {
        final CreatedTask older = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        Thread.sleep(20);
        final CreatedTask urgent = this.db.submit(TaskTestDatabase.FLAT_TYPE, 1);
        final AttemptSettings settings = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

        assertThat(this.firstClaimed(ClaimForm.DEADLINE, 0.5, settings)).isEqualTo(urgent.id());
        assertThat(this.firstClaimed(ClaimForm.PRIORITY, 0.5, settings)).isEqualTo(urgent.id());
        assertThat(this.firstClaimed(ClaimForm.QUOTA, 0.5, settings)).isEqualTo(urgent.id());
        assertThat(this.firstClaimed(ClaimForm.QUOTA, 0.01, settings)).isEqualTo(older.id());
    }

    /** Захватывает одну задачу и возвращает обе в исходное состояние для следующей формы. */
    private UUID firstClaimed(final ClaimForm form, final double random, final AttemptSettings settings) {
        final ClaimedAttempt attempt = this.db
                .claimer("worker-a", settings, () -> random)
                .claim(TaskTestSettings.mainPoller(form), FLAT, 1)
                .getFirst();
        this.db.jdbc.sql("DELETE FROM tc_task_attempt").update();
        this.db.jdbc.sql("UPDATE tc_task SET status = 'PENDING'").update();
        return attempt.target().id();
    }

    private Map<String, Object> attempt(final UUID id) {
        return this.db
                .jdbc
                .sql("SELECT status, status_description, locked_by, phase, finished_at FROM tc_task_attempt"
                        + " WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }

    private static Callable<Integer> drain(
            final TaskClaimer claimer, final List<UUID> claimed, final CountDownLatch start) {
        return () -> {
            start.await();
            int total = 0;
            List<ClaimedAttempt> batch;
            do {
                batch = claimer.claim(DEADLINE, FLAT, 3);
                total += batch.size();
                batch.forEach(attempt -> claimed.add(attempt.target().id()));
            } while (!batch.isEmpty());
            return total;
        };
    }
}
