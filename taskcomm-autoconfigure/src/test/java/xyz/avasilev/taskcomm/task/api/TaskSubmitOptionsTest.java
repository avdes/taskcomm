package xyz.avasilev.taskcomm.task.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/** Опции постановки: неизменяемы, дело и новое дело взаимно заменяют друг друга (docs/adr/0111). */
class TaskSubmitOptionsTest {

    private static final ExecutionRef EXECUTION = new ExecutionRef(UUID.randomUUID(), Instant.EPOCH);

    @Test
    void defaultsAreEmpty() {
        final TaskSubmitOptions options = TaskSubmitOptions.defaults();

        assertThat(options.executionRef()).isEmpty();
        assertThat(options.newExecution()).isFalse();
        assertThat(options.notBefore()).isEmpty();
        assertThat(options.priority()).isEmpty();
    }

    @Test
    void settersReturnNewValuesAndKeepTheRest() {
        final Instant tomorrow = Instant.parse("2026-10-25T06:00:00Z");
        final TaskSubmitOptions base = TaskSubmitOptions.defaults().notBefore(tomorrow);
        final TaskSubmitOptions derived = base.inExecution(EXECUTION).priority(10);

        assertThat(base.executionRef()).isEmpty();
        assertThat(derived.executionRef()).contains(EXECUTION);
        assertThat(derived.notBefore()).contains(tomorrow);
        assertThat(derived.priority()).contains(10);
    }

    @Test
    void executionAndNewExecutionReplaceEachOther() {
        final TaskSubmitOptions fresh =
                TaskSubmitOptions.defaults().inExecution(EXECUTION).inNewExecution();
        final TaskSubmitOptions bound =
                TaskSubmitOptions.defaults().inNewExecution().inExecution(EXECUTION);

        assertThat(fresh.newExecution()).isTrue();
        assertThat(fresh.executionRef()).isEmpty();
        assertThat(bound.newExecution()).isFalse();
        assertThat(bound.executionRef()).contains(EXECUTION);
    }

    @Test
    void priorityMustBePositive() {
        assertThatThrownBy(() -> TaskSubmitOptions.defaults().priority(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("больше нуля");
    }
}
