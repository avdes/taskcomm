package xyz.avasilev.taskcomm.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.Execution;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.schema.internal.TaskcommSchemaInitializer;

/** Таблица дел: время создания — от базы через {@code RETURNING}, поиск — по всем партициям. */
class ExecutionRepositoryIT {

    private ExecutionRepository executions;

    @BeforeEach
    void createSchema() {
        final DataSource dataSource = TestPostgres.dataSource(TestPostgres.newSchema());
        new TaskcommSchemaInitializer(dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
        final JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE TABLE tc_execution_default PARTITION OF tc_execution DEFAULT")
                .update();
        this.executions = new ExecutionRepository(jdbc, new UuidV7(Clock.systemUTC()));
    }

    @Test
    void createdExecutionIsFoundWithDatabaseCreationTime() {
        final ExecutionRef ref = this.executions.create();

        assertThat(this.executions.find(ref.id())).map(Execution::ref).contains(ref);
    }

    @Test
    void unknownExecutionIsNotFound() {
        assertThat(this.executions.find(UUID.randomUUID())).isEmpty();
    }
}
