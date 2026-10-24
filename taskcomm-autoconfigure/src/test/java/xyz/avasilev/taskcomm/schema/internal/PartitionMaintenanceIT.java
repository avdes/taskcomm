package xyz.avasilev.taskcomm.schema.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.schema.internal.PartitionCatalog.ExistingPartition;

/**
 * Обслуживание партиций и граница активных партиций на настоящем PostgreSQL
 * (docs/adr/0078, docs/adr/0080, docs/adr/0101).
 */
class PartitionMaintenanceIT {

    private static final RangeCalendar MONTH = RangeCalendar.MONTH;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private Instant current;

    @BeforeEach
    void createSchema() {
        this.dataSource = TestPostgres.dataSource(TestPostgres.newSchema());
        new TaskcommSchemaInitializer(this.dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
        this.jdbc = JdbcClient.create(this.dataSource);
        this.current = MONTH.floor(SchemaSql.databaseNow(this.jdbc));
    }

    @Test
    void startupCreatesCurrentAndAheadRangesForAllNineTablesWithTheirIndexes() {
        this.maintenance(true, 2, false).startup();

        final List<ExistingPartition> partitions = this.partitions();
        assertThat(partitions).hasSize(9 * 3).allMatch(partition -> partition.matches(MONTH));
        assertThat(partitions)
                .extracting(ExistingPartition::lower)
                .containsOnly(this.current, MONTH.next(this.current), MONTH.next(MONTH.next(this.current)));
        assertThat(partitions)
                .extracting(ExistingPartition::name)
                .contains(RangeCalendar.partitionName("tc_communication_attempt", this.current));
        final String taskPartition = RangeCalendar.partitionName("tc_task", this.current);
        assertThat(this.indexCount(taskPartition)).isEqualTo(this.indexCount("tc_task"));
    }

    @Test
    void startupIsRepeatable() {
        this.maintenance(true, 1, false).startup();
        this.maintenance(true, 1, false).startup();

        assertThat(this.partitions()).hasSize(9 * 2);
    }

    @Test
    void startupFailsWhenBoundariesDoNotMatchPeriod() {
        final Instant lower = MONTH.next(MONTH.next(MONTH.next(MONTH.next(this.current))));
        this.jdbc
                .sql("CREATE TABLE week_partition PARTITION OF tc_task FOR VALUES FROM ("
                        + SchemaSql.literal(lower) + ") TO (" + SchemaSql.literal(lower.plus(7, ChronoUnit.DAYS))
                        + ")")
                .update();

        assertThatThrownBy(() -> this.maintenance(true, 1, false).startup())
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("не совпадают с taskcomm.schema.partitions.period = month")
                .hasMessageContaining("week_partition");
    }

    @Test
    void unmanagedStartupRequiresCurrentPartitionAndCreatesNothing() {
        assertThatThrownBy(() -> this.maintenance(false, 1, false).startup())
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("Нет партиции на текущий момент")
                .hasMessageContaining("tc_execution");

        this.createRange(this.current);
        this.maintenance(false, 1, false).startup();

        assertThat(this.partitions()).hasSize(9);
    }

    @Test
    void dropKeepsRangeWithUnfinishedWorkAndDropsItOnceFinished() {
        final Instant old = MONTH.floor(this.current.minus(400, ChronoUnit.DAYS));
        this.createRange(old);
        final PartitionMaintenance maintenance = this.maintenance(true, 1, true);
        maintenance.startup();
        this.insertTask(old, "RUNNING");
        this.insertCorrelation(old.plus(1, ChronoUnit.DAYS));
        this.insertCorrelation(this.current);

        maintenance.tick();
        assertThat(this.partitions()).extracting(ExistingPartition::lower).contains(old);

        this.jdbc.sql("UPDATE tc_task SET status = 'SUCCEEDED'").update();
        maintenance.tick();

        assertThat(this.partitions()).extracting(ExistingPartition::lower).doesNotContain(old);
        assertThat(this.jdbc
                        .sql("SELECT count(*) FROM tc_communication_correlation")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void dropGivesUpQuicklyWhenPartitionIsBusyAndRetriesOnNextTick() throws Exception {
        final Instant old = MONTH.floor(this.current.minus(400, ChronoUnit.DAYS));
        this.createRange(old);
        final PartitionMaintenance maintenance = this.maintenance(true, 1, true);
        maintenance.startup();

        try (Connection reader = this.dataSource.getConnection();
                Statement statement = reader.createStatement()) {
            reader.setAutoCommit(false);
            statement.execute(
                    "LOCK TABLE " + RangeCalendar.partitionName("tc_task_result", old) + " IN ACCESS SHARE MODE");
            final long started = System.nanoTime();
            maintenance.tick();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(this.partitions()).extracting(ExistingPartition::lower).contains(old);
            // Занятая партиция — штатное «отложено», а не сбой такта: остальная работа такта сделана.
            assertThat(this.horizon()).isEqualTo(this.current);
            reader.rollback();
        }

        maintenance.tick();
        assertThat(this.partitions()).extracting(ExistingPartition::lower).doesNotContain(old);
    }

    @Test
    void horizonStaysAtUnfinishedRangeAndMovesToCurrentOnceItFinishes() {
        final Instant old = MONTH.floor(this.current.minus(100, ChronoUnit.DAYS));
        this.createRange(old);
        this.insertTask(old, "PENDING");
        final PartitionMaintenance maintenance = this.maintenance(true, 1, false);

        maintenance.startup();
        assertThat(this.horizon()).isEqualTo(old);

        this.jdbc.sql("UPDATE tc_task SET status = 'SUCCEEDED'").update();
        maintenance.tick();

        assertThat(this.horizon()).isEqualTo(this.current);
    }

    @Test
    void horizonStartsAtCurrentPeriodWhenNoOlderPartitionsExist() {
        this.maintenance(true, 1, false).startup();

        assertThat(this.horizon()).isEqualTo(this.current);
    }

    private PartitionMaintenance maintenance(final boolean manage, final int createAhead, final boolean drop) {
        final TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(this.dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new PartitionMaintenance(
                this.jdbc,
                transactions,
                new PartitionSettings(manage, MONTH, createAhead, Duration.ofHours(1), drop, Duration.ofDays(1)));
    }

    private List<ExistingPartition> partitions() {
        return new PartitionCatalog(this.jdbc).partitions();
    }

    private Instant horizon() {
        return new PartitionHorizon(this.jdbc, null, MONTH).read().orElseThrow();
    }

    private int indexCount(final String table) {
        return this.jdbc
                .sql("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND tablename = :table")
                .param("table", table)
                .query(Integer.class)
                .single();
    }

    private void createRange(final Instant lower) {
        for (final PartitionedTable table : PartitionedTable.values()) {
            this.jdbc
                    .sql("CREATE TABLE " + RangeCalendar.partitionName(table.tableName(), lower) + " PARTITION OF "
                            + table.tableName() + " FOR VALUES FROM (" + SchemaSql.literal(lower) + ") TO ("
                            + SchemaSql.literal(MONTH.next(lower)) + ")")
                    .update();
        }
    }

    private void insertTask(final Instant executionCreatedAt, final String status) {
        this.jdbc
                .sql("""
                        INSERT INTO tc_task (id, created_at, updated_at, execution_id, execution_created_at, task_type,
                                             payload, status, next_attempt_at, claim_order_at, retry_cycle_started_at)
                        VALUES (gen_random_uuid(), now(), now(), gen_random_uuid(), :at, 'test',
                                '{}', :status, now(), now(), now())
                        """)
                .param("at", SchemaSql.parameter(executionCreatedAt))
                .param("status", status)
                .update();
    }

    private void insertCorrelation(final Instant executionCreatedAt) {
        this.jdbc.sql("""
                        INSERT INTO tc_communication_correlation (id, created_at, updated_at, external_system,
                                                                  correlation_id, communication_id, execution_id,
                                                                  execution_created_at)
                        VALUES (gen_random_uuid(), now(), now(), 'supplier', gen_random_uuid()::text,
                                gen_random_uuid(), gen_random_uuid(), :at)
                        """).param("at", SchemaSql.parameter(executionCreatedAt)).update();
    }
}
