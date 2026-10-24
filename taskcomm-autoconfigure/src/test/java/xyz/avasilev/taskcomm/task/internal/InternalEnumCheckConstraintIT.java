package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code CHECK} в схеме дублирует и внутренние перечисления подсистемы задач
 * (docs/rules/persistence.md): уровень, фаза, форма контракта и исход попытки.
 */
class InternalEnumCheckConstraintIT {

    private static final Pattern VALUE = Pattern.compile("'([A-Z_]+)'::text");

    private static JdbcClient jdbc;

    @BeforeAll
    static void createSchema() {
        jdbc = TaskTestDatabase.create().jdbc;
    }

    @Test
    void levelMatchesChecksOfAttemptsAndResults() {
        for (final String constraint : List.of("tc_task_attempt_level_check", "tc_task_result_level_check")) {
            assertThat(checkValues(constraint)).containsExactlyInAnyOrderElementsOf(names(TaskLevel.values()));
        }
    }

    @Test
    void phaseMatchesChecksOfAllPhaseColumns() {
        for (final String constraint :
                List.of("tc_task_phase_check", "tc_task_batch_phase_check", "tc_task_attempt_phase_check")) {
            assertThat(checkValues(constraint)).containsExactlyInAnyOrderElementsOf(names(TaskPhase.values()));
        }
    }

    @Test
    void contractShapeMatchesCheck() {
        assertThat(checkValues("tc_task_contract_shape_check"))
                .containsExactlyInAnyOrderElementsOf(names(ContractShape.values()));
    }

    @Test
    void attemptStatusMatchesCheck() {
        assertThat(checkValues("tc_task_attempt_status_check"))
                .containsExactlyInAnyOrderElementsOf(names(AttemptStatus.values()));
    }

    private static List<String> checkValues(final String constraint) {
        final String definition =
                jdbc.sql("""
                        SELECT pg_get_constraintdef(con.oid)
                          FROM pg_constraint con
                          JOIN pg_class c ON c.oid = con.conrelid
                         WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind = 'p' AND con.conname = :name
                        """).param("name", constraint).query(String.class).single();
        final Matcher matcher = VALUE.matcher(definition);
        final List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static List<String> names(final Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
