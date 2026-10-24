package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenance;
import xyz.avasilev.taskcomm.schema.internal.PartitionSettings;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;
import xyz.avasilev.taskcomm.schema.internal.TaskcommSchemaInitializer;

/**
 * Схема taskcomm в отдельной схеме общего контейнера PostgreSQL, с партициями на текущий месяц
 * и запасом, и сборка компонентов подсистемы задач поверх неё.
 */
final class TaskTestDatabase {

    /** Девять партиционированных таблиц — для заведения старых диапазонов. */
    static final List<String> PARTITIONED = List.of(
            "tc_execution",
            "tc_task",
            "tc_task_batch",
            "tc_task_unit",
            "tc_communication",
            "tc_task_attempt",
            "tc_communication_attempt",
            "tc_task_result",
            "tc_communication_dedup");

    /** Тип плоской задачи по умолчанию в тестах. */
    static final String FLAT_TYPE = "report";

    final DataSource dataSource;

    final JdbcClient jdbc;

    final PlatformTransactionManager transactionManager;

    final UuidV7 ids = new UuidV7(java.time.Clock.systemUTC());

    final Instant currentMonth;

    private final PartitionMaintenance maintenance;

    private TaskTestDatabase(final DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
        new TaskcommSchemaInitializer(dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
        final TransactionTemplate transactions = new TransactionTemplate(this.transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.maintenance = new PartitionMaintenance(
                this.jdbc,
                transactions,
                new PartitionSettings(true, RangeCalendar.MONTH, 3, Duration.ofHours(1), false, Duration.ofDays(180)));
        this.currentMonth = RangeCalendar.MONTH.floor(this.now());
    }

    /**
     * Новая схема с партициями текущего месяца и запасом.
     *
     * @return база для одного теста
     */
    static TaskTestDatabase create() {
        final TaskTestDatabase database = new TaskTestDatabase(TestPostgres.dataSource(TestPostgres.newSchema()));
        database.maintenance.startup();
        return database;
    }

    /**
     * Заводит партиции всех девяти таблиц на месяц, начинающийся с {@code lower}, и пересчитывает
     * границу активных партиций: пустой старый месяц она сразу закрывает.
     *
     * @param lower начало месяца
     */
    void createMonth(final Instant lower) {
        for (final String table : PARTITIONED) {
            this.jdbc
                    .sql("CREATE TABLE " + RangeCalendar.partitionName(table, lower) + " PARTITION OF " + table
                            + " FOR VALUES FROM ('" + lower.atOffset(ZoneOffset.UTC) + "') TO ('"
                            + RangeCalendar.MONTH.next(lower).atOffset(ZoneOffset.UTC) + "')")
                    .update();
        }
        this.maintenance.startup();
    }

    /** Такт обслуживания: сдвигает границу вперёд по свободным диапазонам. */
    void advanceHorizon() {
        this.maintenance.startup();
    }

    Instant horizon() {
        return TaskPartitionHorizon.activeFrom(this.jdbc);
    }

    Instant now() {
        return this.jdbc
                .sql("SELECT now()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    TaskCreator creator() {
        return new TaskCreator(
                this.dataSource,
                this.transactionManager,
                new ExecutionRepository(this.jdbc, this.ids),
                this.ids,
                PartitionRange.MONTH);
    }

    TaskClaimer claimer(final String instance, final AttemptSettings settings, final DoubleSupplier random) {
        return new TaskClaimer(
                this.dataSource,
                this.transactionManager,
                this.ids,
                AttemptSettingsSource.uniform(settings),
                TaskTestSettings.layout(),
                random,
                instance);
    }

    TaskAttemptCloser closer(final AttemptSettings settings) {
        return new TaskAttemptCloser(
                this.dataSource,
                this.transactionManager,
                this.ids,
                AttemptSettingsSource.uniform(settings),
                TaskTestSettings.layout(),
                () -> 0.5);
    }

    /**
     * Ставит задачу в новое дело.
     *
     * @param type тип задачи
     * @param priority приоритет
     * @return созданная задача
     */
    CreatedTask submit(final String type, final int priority) {
        return this.creator().create(new NewTask(type, "{\"n\":1}", priority, Duration.ofMinutes(1), null, null, null));
    }

    /**
     * Ставит задачу в существующее дело.
     *
     * @param execution дело
     * @return созданная задача
     */
    CreatedTask submitInto(final ExecutionRef execution) {
        return this.creator()
                .create(new NewTask(FLAT_TYPE, "{\"n\":1}", 100, Duration.ofMinutes(1), null, execution, null));
    }

    String status(final TaskLevel level, final UUID id) {
        return this.jdbc
                .sql("SELECT status FROM " + level.table() + " WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    int count(final String sql, final UUID id) {
        return this.jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }
}
