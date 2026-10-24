package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.CurrentExecution;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;

/**
 * Оживление старой партиции на путях записи подсистемы задач
 * (docs/adr/0101-active-partition-horizon.md): постановка в существующее дело и задача, поставленная
 * из процессора сущности старого дела. Дети раскрытия и перезапуск добавят свои тесты сюда же.
 */
class TaskPartitionRevivalIT {

    private TaskTestDatabase db;

    private Instant old;

    @BeforeEach
    void createSchemaWithClosedOldMonth() {
        this.db = TaskTestDatabase.create();
        this.old = RangeCalendar.MONTH.floor(this.db.currentMonth.minus(40, ChronoUnit.DAYS));
        this.db.createMonth(this.old);
        assertThat(this.db.horizon()).isEqualTo(this.db.currentMonth);
    }

    @Test
    void submissionIntoOldExecutionLowersHorizonAndKeepsItThere() {
        final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), this.old.plus(3, ChronoUnit.DAYS));

        final CreatedTask task = this.db.submitInto(execution);

        assertThat(this.db.horizon()).isEqualTo(this.old);
        assertThat(this.db.count(
                        "SELECT count(*) FROM " + RangeCalendar.partitionName("tc_task", this.old) + " WHERE id = :id",
                        task.id()))
                .isEqualTo(1);
        this.db.advanceHorizon();
        assertThat(this.db.horizon()).isEqualTo(this.old);
    }

    @Test
    void taskSubmittedFromProcessorOfOldExecutionLowersHorizonAndKeepsOrigin() {
        final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), this.old.plus(5, ChronoUnit.DAYS));
        final Origin creator =
                new Origin(OriginType.TASK, UUID.randomUUID(), UUID.randomUUID(), OriginRelation.SPAWNED);
        final TaskProcessorRegistry registry = new TaskProcessorRegistry(List.of(new Report()));
        final TaskSettings settings = TaskSettings.resolve(
                TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(1)),
                Map.of(),
                registry,
                TaskTestSettings.layout());
        final DefaultTaskService service = new DefaultTaskService(
                () -> registry,
                () -> settings,
                this.db.creator(),
                new TaskPayloadCodec(JsonMapper.builder().build()),
                this.db.dataSource);

        final Task<String> task;
        try (CurrentExecution.Scope _ = CurrentExecution.bind(new CurrentExecution.Binding(execution, creator))) {
            task = service.submit(TaskTestDatabase.FLAT_TYPE, "дочерняя");
        }

        assertThat(task.executionRef()).isEqualTo(execution);
        assertThat(task.origin()).contains(creator);
        assertThat(this.db.horizon()).isEqualTo(this.old);
        assertThat(this.db.count(
                        "SELECT count(*) FROM " + RangeCalendar.partitionName("tc_task", this.old)
                                + " WHERE id = :id AND origin_id IS NOT NULL",
                        task.id()))
                .isEqualTo(1);
    }

    @Test
    void submissionIntoCurrentExecutionLeavesHorizonAlone() {
        final CreatedTask first = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);

        this.db.submitInto(first.execution());

        assertThat(this.db.horizon()).isEqualTo(this.db.currentMonth);
    }

    @Test
    void revivalHoldsSchemaRangeLockUntilCommit() {
        final JdbcClient other = JdbcClient.create(TestPostgres.dataSource("public"));
        final int rangeKey = RangeCalendar.rangeKey(this.old);
        final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), this.old.plus(10, ChronoUnit.DAYS));

        new TransactionTemplate(this.db.transactionManager).executeWithoutResult(status -> {
            this.db.submitInto(execution);
            assertThat(tryExclusive(other, rangeKey)).isFalse();
        });

        assertThat(tryExclusive(other, rangeKey)).isTrue();
    }

    @Test
    void rangeLowerBoundAndKeyMatchSchemaCalendarForEveryPeriod() {
        final Instant moment = Instant.parse("2026-03-05T23:30:00Z");
        for (final PartitionRange range : PartitionRange.values()) {
            final RangeCalendar calendar = RangeCalendar.valueOf(range.name());
            this.db
                    .jdbc
                    .sql("UPDATE tc_partition_horizon SET active_from = 'infinity'")
                    .update();

            final Long locks = new TransactionTemplate(this.db.transactionManager).execute(status -> {
                new TaskPartitionHorizon(this.db.jdbc, range).revive(moment);
                return this.db
                        .jdbc
                        .sql("""
                                SELECT count(*) FROM pg_locks
                                 WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND mode = 'ShareLock'
                                   AND classid = CAST(:key AS oid) AND objid = CAST(:subkey AS oid) AND objsubid = 2
                                """)
                        .param("key", TaskPartitionHorizon.HORIZON_RANGE_LOCK)
                        .param("subkey", RangeCalendar.rangeKey(calendar.floor(moment)))
                        .query(Long.class)
                        .single();
            });

            assertThat(locks).as(range.name()).isEqualTo(1);
            assertThat(this.db.horizon()).as(range.name()).isEqualTo(calendar.floor(moment));
        }
    }

    /** Так пробует блокировку такт границы — исключительно, не дожидаясь; отпускает конец оператора. */
    private static boolean tryExclusive(final JdbcClient jdbc, final int rangeKey) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key, :subkey)")
                .param("key", TaskPartitionHorizon.HORIZON_RANGE_LOCK)
                .param("subkey", rangeKey)
                .query(Boolean.class)
                .single());
    }

    /** Плоский процессор типа задачи по умолчанию. */
    static class Report implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return TaskTestDatabase.FLAT_TYPE;
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }
}
