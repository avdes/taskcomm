package xyz.avasilev.taskcomm.task.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.schema.internal.TaskcommSchemaInitializer;

/**
 * {@code CHECK} в схеме дублирует публичные перечисления, и копии могут разойтись
 * (docs/rules/persistence.md): тест сверяет набор значений ограничения с перечислением.
 */
class EnumCheckConstraintIT {

    private static final Pattern VALUE = Pattern.compile("'([A-Z_]+)'::text");

    private static JdbcClient jdbc;

    @BeforeAll
    static void createSchema() {
        final DataSource dataSource = TestPostgres.dataSource(TestPostgres.newSchema());
        new TaskcommSchemaInitializer(dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void taskStatusMatchesCheck() {
        assertThat(checkValues("tc_task_status_check")).containsExactlyInAnyOrderElementsOf(names(TaskStatus.values()));
    }

    @Test
    void taskBatchStatusMatchesCheck() {
        assertThat(checkValues("tc_task_batch_status_check"))
                .containsExactlyInAnyOrderElementsOf(names(TaskBatchStatus.values()));
    }

    @Test
    void taskUnitStatusMatchesCheck() {
        assertThat(checkValues("tc_task_unit_status_check"))
                .containsExactlyInAnyOrderElementsOf(names(TaskUnitStatus.values()));
    }

    @Test
    void originTypeAndRelationMatchChecksOfBothTables() {
        for (final String table : List.of("tc_task", "tc_communication")) {
            assertThat(checkValues(table + "_origin_type_check"))
                    .containsExactlyInAnyOrderElementsOf(names(OriginType.values()));
            assertThat(checkValues(table + "_origin_relation_check"))
                    .containsExactlyInAnyOrderElementsOf(names(OriginRelation.values()));
        }
    }

    @Test
    void fieldTypeMatchesCheck() {
        assertThat(checkValues("tc_searchable_field_field_type_check"))
                .containsExactlyInAnyOrderElementsOf(names(FieldType.values()));
    }

    private static List<String> checkValues(final String constraint) {
        final String definition =
                jdbc.sql("""
                        SELECT pg_get_constraintdef(con.oid)
                          FROM pg_constraint con
                          JOIN pg_class c ON c.oid = con.conrelid
                         WHERE c.relnamespace = current_schema()::regnamespace AND con.conname = :name
                        """).param("name", constraint).query(String.class).single();
        final Matcher matcher = VALUE.matcher(definition);
        final List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static List<String> names(final Enum<?>... values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
