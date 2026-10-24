package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сверка формы контракта при захвате детей и подстраховочный поллер на уровне хранилища
 * (docs/adr/0072-contract-shape-check-on-children.md, docs/adr/0126-safety-poller.md). Падение
 * экземпляра между коммитом и каскадом изображается правкой строк напрямую.
 */
class TaskSafetyIT {

    private static final String TYPE = "orders";

    private static final Map<String, ContractShape> UNIT = Map.of(TYPE, ContractShape.UNIT);

    private static final PollerSettings DEADLINE = TaskTestSettings.mainPoller(ClaimForm.DEADLINE);

    private static final AttemptSettings SETTINGS = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1));

    private TaskTestDatabase db;

    private TaskClaimer claimer;

    private TaskAttemptCloser closer;

    private TaskSafetyPoller safety;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
        this.claimer = this.db.claimer("worker", SETTINGS, () -> 0.5);
        this.closer = this.db.closer(SETTINGS);
        final TaskChildren children = this.db.children();
        this.safety = new TaskSafetyPoller(
                this.db.dataSource,
                children,
                new TaskCancellation(this.db.dataSource, this.db.transactionManager, children),
                new SafetySettings(Duration.ofMinutes(1), Duration.ofMillis(1)));
    }

    @Test
    void childOfTaskStartedUnderAnotherShapeIsSkippedAndTaskCancelled() {
        final CreatedTask task = this.db.submit(TYPE, 100);
        this.expand(2, true);
        this.db
                .jdbc
                .sql("UPDATE tc_task SET contract_shape = 'BATCH' WHERE id = :id")
                .param("id", task.id())
                .update();

        assertThat(this.claimer.claim(DEADLINE, UNIT, 10)).isEmpty();

        assertThat(this.units(task.id(), "SKIPPED")).isEqualTo(2);
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("CANCELLED");
        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task WHERE id = :id AND cancel_requested_at IS NOT NULL"
                                + " AND status_description = 'форма контракта BATCH не совпадает с текущей UNIT'",
                        task.id()))
                .isEqualTo(1);
    }

    @Test
    void safetyReleasesChildrenOfClosedExpansionLeftAwaitingParent() throws Exception {
        final CreatedTask task = this.db.submit(TYPE, 100);
        this.expand(2, false);
        Thread.sleep(20);

        this.safety.tick();

        assertThat(this.units(task.id(), "PENDING")).isEqualTo(2);
    }

    @Test
    void safetyHandsOverParentWhoseChildrenAllEnded() throws Exception {
        final CreatedTask task = this.db.submit(TYPE, 100);
        this.expand(1, true);
        final ClaimedAttempt unit = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        this.db
                .jdbc
                .sql("UPDATE tc_task_attempt SET status = 'SUCCEEDED', finished_at = now() WHERE id = :id")
                .param("id", unit.lease().attemptId())
                .update();
        this.db
                .jdbc
                .sql("UPDATE tc_task_unit SET status = 'SUCCEEDED' WHERE id = :id")
                .param("id", unit.target().id())
                .update();
        Thread.sleep(20);
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("AWAITING_CHILDREN");

        this.safety.tick();

        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");
        assertThat(this.db.count("SELECT count(*) FROM tc_task WHERE id = :id AND phase = 'COLLECT'", task.id()))
                .isEqualTo(1);
    }

    @Test
    void safetyFinishesInterruptedCancellation() throws Exception {
        final CreatedTask task = this.db.submit(TYPE, 100);
        this.expand(2, true);
        this.db
                .jdbc
                .sql("UPDATE tc_task SET cancel_requested_at = now() WHERE id = :id")
                .param("id", task.id())
                .update();
        Thread.sleep(20);

        this.safety.tick();

        assertThat(this.units(task.id(), "SKIPPED")).isEqualTo(2);
        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("CANCELLED");
        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task WHERE id = :id AND status_description = 'отменено'", task.id()))
                .isEqualTo(1);
    }

    /**
     * Раскрывает захваченную задачу в {@code count} подзадач.
     *
     * @param count сколько подзадач
     * @param cascade закрыть попытку штатно, с каскадом; иначе — как будто экземпляр упал после
     *     коммита закрытия: попытка успешна, задача ждёт детей, дети остались в {@code AWAITING_PARENT}
     */
    private void expand(final int count, final boolean cascade) {
        final ClaimedAttempt expansion = this.claimer.claim(DEADLINE, UNIT, 1).getFirst();
        final TaskEmitter emitter = new TaskEmitter(
                expansion,
                TaskLevel.UNIT,
                this.db.children(),
                new TaskPayloadCodec(JsonMapper.builder().build()),
                this.db.ids);
        for (int i = 0; i < count; i++) {
            emitter.emit(i);
        }
        emitter.finish();
        if (cascade) {
            this.closer.expanded(expansion, emitter.flushed());
        } else {
            this.db
                    .jdbc
                    .sql("UPDATE tc_task_attempt SET status = 'SUCCEEDED', finished_at = now() WHERE id = :id")
                    .param("id", expansion.lease().attemptId())
                    .update();
            this.db
                    .jdbc
                    .sql("UPDATE tc_task SET status = 'AWAITING_CHILDREN', phase = 'COLLECT' WHERE id = :id")
                    .param("id", expansion.target().id())
                    .update();
        }
    }

    private int units(final UUID taskId, final String status) {
        return this.db.count(
                "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = '" + status + "'", taskId);
    }
}
