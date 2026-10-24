package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;

/**
 * Создание задачи: всегда в транзакции, время базы, новое дело через {@code RETURNING}
 * (docs/adr/0079-database-clock.md, docs/adr/0094-creation-always-in-transaction.md).
 */
class TaskCreatorIT {

    private TaskTestDatabase db;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
    }

    @Test
    void newExecutionTakesItsCreationTimeFromDatabase() {
        final CreatedTask task = this.db.submit(TaskTestDatabase.FLAT_TYPE, 7);

        final Map<String, Object> row =
                this.db.jdbc.sql("""
                        SELECT e.created_at AS execution_created_at, t.execution_created_at AS task_partition_key,
                               t.status, t.priority, t.created_at,
                               extract(epoch FROM t.claim_order_at - t.next_attempt_at) AS offset_seconds
                          FROM tc_task t JOIN tc_execution e ON e.id = t.execution_id
                         WHERE t.id = :id
                        """).param("id", task.id()).query().singleRow();
        assertThat(((Timestamp) row.get("execution_created_at")).toInstant())
                .isEqualTo(task.execution().createdAt());
        assertThat(((Timestamp) row.get("task_partition_key")).toInstant())
                .isEqualTo(task.execution().createdAt());
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(task.createdAt());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("offset_seconds")).doubleValue()).isEqualTo(7 * 60.0);
    }

    @Test
    void notBeforeBecomesNextAttemptAt() {
        final Instant tomorrow = this.db.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        final CreatedTask task = this.db
                .creator()
                .create(new NewTask(
                        TaskTestDatabase.FLAT_TYPE, "{}", 100, Duration.ofMinutes(1), tomorrow, null, null));

        assertThat(this.db
                        .jdbc
                        .sql("SELECT next_attempt_at FROM tc_task WHERE id = :id")
                        .param("id", task.id())
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(tomorrow);
    }

    @Test
    void existingExecutionKeepsPartitionKeyAndOrigin() {
        final CreatedTask parent = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
        final UUID attempt = UUID.randomUUID();
        final CreatedTask child = this.db
                .creator()
                .create(new NewTask(
                        TaskTestDatabase.FLAT_TYPE,
                        "{}",
                        100,
                        Duration.ofMinutes(1),
                        null,
                        parent.execution(),
                        new Origin(OriginType.TASK, parent.id(), attempt, OriginRelation.SPAWNED)));

        assertThat(child.execution()).isEqualTo(parent.execution());
        assertThat(this.db.count("SELECT count(*) FROM tc_execution", parent.id()))
                .isEqualTo(1);
        assertThat(this.db.count(
                        "SELECT count(*) FROM tc_task WHERE id = :id AND origin_type = 'TASK'"
                                + " AND origin_relation = 'SPAWNED' AND origin_attempt_id IS NOT NULL",
                        child.id()))
                .isEqualTo(1);
    }

    @Test
    void joinsConsumerTransactionAndRollsBackWithIt() {
        final TransactionTemplate consumer = new TransactionTemplate(this.db.transactionManager);
        final UUID[] created = new UUID[1];

        consumer.executeWithoutResult(status -> {
            created[0] = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100).id();
            status.setRollbackOnly();
        });

        assertThat(this.db.count("SELECT count(*) FROM tc_task WHERE id = :id", created[0]))
                .isZero();
        assertThat(this.db.count("SELECT count(*) FROM tc_execution", created[0]))
                .isZero();
    }

    @Test
    void refusesConsumerTransactionOnAnotherDataSource() {
        final DataSource business = TestPostgres.dataSource(TestPostgres.newSchema());
        final TransactionTemplate consumer = new TransactionTemplate(new DataSourceTransactionManager(business));

        assertThatThrownBy(
                        () -> consumer.executeWithoutResult(status -> this.db.submit(TaskTestDatabase.FLAT_TYPE, 100)))
                .isInstanceOf(TaskcommException.class)
                .hasMessageContaining("@TaskcommDataSource");
        assertThat(this.db.count("SELECT count(*) FROM tc_task", null)).isZero();
    }

    @Test
    void outsideTransactionOpensItsOwnReadCommitted() {
        final CreatedTask task = this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);

        assertThat(this.db.status(TaskLevel.TASK, task.id())).isEqualTo("PENDING");
    }

    @Test
    void originWithoutExecutionIsRejected() {
        assertThatThrownBy(() -> new NewTask(
                        "t",
                        "{}",
                        100,
                        Duration.ofMinutes(1),
                        null,
                        null,
                        new Origin(OriginType.TASK, UUID.randomUUID(), null, OriginRelation.SPAWNED)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
