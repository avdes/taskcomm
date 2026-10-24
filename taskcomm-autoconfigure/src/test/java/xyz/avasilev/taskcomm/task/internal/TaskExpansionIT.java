package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Дети раскрытия и сборка на уровне хранилища (docs/adr/0068-expansion-children-statuses.md,
 * docs/adr/0069-parent-phase-column.md, docs/adr/0122-child-results-discard-sweep.md): сброс
 * буфера после потери аренды, передача родителя в сборку последним ребёнком, агрегат детей,
 * удаление результатов детей подметальщиком по сроку.
 */
class TaskExpansionIT {

    private static final String TYPE = "orders";

    private static final Map<String, ContractShape> UNIT = Map.of(TYPE, ContractShape.UNIT);

    private static final PollerSettings DEADLINE = TaskTestSettings.mainPoller(ClaimForm.DEADLINE);

    private static final AttemptSettings SETTINGS = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

    private TaskTestDatabase db;

    private TaskClaimer claimer;

    private TaskAttemptCloser closer;

    private final TaskPayloadCodec codec =
            new TaskPayloadCodec(JsonMapper.builder().build());

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
        this.claimer = this.db.claimer("worker", SETTINGS, () -> 0.5);
        this.closer = this.db.closer(SETTINGS);
    }

    @Test
    void flushAfterLostLeaseWritesNothingAndNextEmitFails() {
        final CreatedTask task = this.db.submit(TYPE, 100);
        final ClaimedAttempt expansion = this.claimOne();
        assertThat(expansion.phase()).isEqualTo(TaskPhase.EXPAND);
        this.db
                .jdbc
                .sql("UPDATE tc_task_attempt SET status = 'LEASE_LOST', finished_at = now() WHERE id = :id")
                .param("id", expansion.lease().attemptId())
                .update();
        final TaskEmitter emitter = this.emitter(expansion);

        for (int i = 0; i < TaskEmitter.FLUSH_SIZE; i++) {
            emitter.emit(i);
        }

        assertThatThrownBy(() -> emitter.emit(-1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже закрыта");
        assertThat(emitter.flushed()).isZero();
        assertThat(this.db.count("SELECT count(*) FROM tc_task_unit WHERE task_id = :id", task.id()))
                .isZero();
    }

    @Test
    void lastChildHandsParentToCollectAndCollectSetsAggregate() {
        final CreatedTask task = this.db.submit(TYPE, 100);
        final ClaimedAttempt expansion = this.claimOne();
        final TaskEmitter emitter = this.emitter(expansion);
        emitter.emit(1);
        emitter.emit(2);
        assertThat(emitter.finish()).isTrue();
        assertThat(this.closer.expanded(expansion, emitter.flushed())).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("AWAITING_CHILDREN");
        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'PENDING'", task.id()))
                .isEqualTo(2);

        final List<ClaimedAttempt> units = this.claimer.claim(DEADLINE, UNIT, 10);
        assertThat(units).hasSize(2).allSatisfy(unit -> {
            assertThat(unit.target().level()).isEqualTo(TaskLevel.UNIT);
            assertThat(unit.entity().parent().taskId()).isEqualTo(task.id());
            assertThat(unit.entity().parent().attemptId())
                    .isEqualTo(expansion.lease().attemptId());
        });
        assertThat(this.closer.succeed(units.get(0), "10")).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("AWAITING_CHILDREN");
        assertThat(this.closer.fail(units.get(1), "упал", "битая")).isTrue();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");

        final ClaimedAttempt collect = this.claimOne();
        assertThat(collect.phase()).isEqualTo(TaskPhase.COLLECT);
        assertThat(this.closer.collected(
                        collect, "\"свод\"", TaskLevel.UNIT, expansion.lease().attemptId()))
                .isTrue();
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("WARNING");
    }

    @Test
    void sweepDiscardsChildResultsOnlyAfterDelay() {
        final CreatedTask task = this.db.submit(TYPE, 100);
        final ClaimedAttempt expansion = this.claimOne();
        final TaskEmitter emitter = this.emitter(expansion);
        emitter.emit(1);
        emitter.finish();
        this.closer.expanded(expansion, emitter.flushed());
        this.closer.succeed(this.claimOne(), "10");
        this.closer.collected(
                this.claimOne(), "\"свод\"", TaskLevel.UNIT, expansion.lease().attemptId());
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("SUCCEEDED");

        assertThat(this.db
                        .discard(new ResultDiscard(ResultDiscard.Mode.AFTER, Duration.ofHours(1)))
                        .sweep())
                .isZero();
        assertThat(this.results("UNIT", task.id())).isEqualTo(1);

        assertThat(this.db
                        .discard(new ResultDiscard(ResultDiscard.Mode.AFTER, Duration.ZERO))
                        .sweep())
                .isEqualTo(1);
        assertThat(this.results("UNIT", task.id())).isZero();
        assertThat(this.results("TASK", task.id())).isEqualTo(1);
        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task WHERE id = :id AND child_results_discarded_at IS NOT NULL",
                        task.id()))
                .isEqualTo(1);
        assertThat(this.db
                        .discard(new ResultDiscard(ResultDiscard.Mode.AFTER, Duration.ZERO))
                        .sweep())
                .isZero();
    }

    @Test
    void collectPagesFollowChildIndexWithoutSort() {
        for (final String page : List.of(TaskCollector.UNITS_PAGE, TaskCollector.BATCHES_PAGE)) {
            final String plan = new TransactionTemplate(this.db.transactionManager).execute(status -> {
                this.db.jdbc.sql("SET LOCAL enable_seqscan = off").update();
                this.db.jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
                return String.join(
                        System.lineSeparator(),
                        this.db
                                .jdbc
                                .sql("EXPLAIN " + page)
                                .param("executionCreatedAt", TaskSql.parameter(this.db.currentMonth))
                                .param("attemptId", UUID.randomUUID())
                                .param("after", new UUID(0, 0))
                                .param("page", TaskCollector.PAGE)
                                .query(String.class)
                                .list());
            });
            assertThat(plan).contains("parent_attempt_id_id_idx").doesNotContain("Sort");
        }
    }

    private ClaimedAttempt claimOne() {
        return this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
    }

    private TaskEmitter emitter(final ClaimedAttempt expansion) {
        return new TaskEmitter(expansion, TaskLevel.UNIT, this.db.children(), this.codec, this.db.ids);
    }

    private int results(final String level, final UUID taskId) {
        return this.db.count(
                "SELECT count(*) FROM tc_task_result r WHERE r.level = '" + level + "' AND (r.target_id = :id"
                        + " OR r.target_id IN (SELECT u.id FROM tc_task_unit u WHERE u.task_id = :id))",
                taskId);
    }
}
