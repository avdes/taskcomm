package xyz.avasilev.taskcomm.task.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;

/**
 * Сокращения {@code @TaskPriority}, {@code @TaskLease}, {@code @TaskRetry} читаются как
 * {@code @TaskProcessorSettings} через {@code MergedAnnotations}, и несколько аннотаций на одном
 * месте видны все — стартеру есть что сложить (docs/adr/0113).
 */
class TaskProcessorSettingsTest {

    @Test
    void shortcutsAreReadAsSettings() {
        final List<MergedAnnotation<TaskProcessorSettings>> settings = MergedAnnotations.from(Combined.class).stream(
                        TaskProcessorSettings.class)
                .toList();

        assertThat(settings).hasSize(3);
        assertThat(settings).anySatisfy(s -> assertThat(s.getInt("priority")).isEqualTo(10));
        assertThat(settings).anySatisfy(s -> assertThat(s.getInt("maxAttempts")).isEqualTo(5));
        assertThat(settings)
                .anySatisfy(s -> assertThat(s.getString("maxExecutionTime")).isEqualTo("2h"));
    }

    @Test
    void unsetAttributesAreZeroOrEmpty() {
        final MergedAnnotation<TaskProcessorSettings> retry =
                MergedAnnotations.from(OnlyRetry.class).get(TaskProcessorSettings.class);

        assertThat(retry.getInt("maxAttempts")).isEqualTo(5);
        assertThat(retry.getInt("priority")).isZero();
        assertThat(retry.getString("leaseDuration")).isEmpty();
        assertThat(retry.getDouble("jitter")).isZero();
    }

    @Test
    void priorityShortcutIsClassOnly() {
        assertThat(TaskPriority.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);
    }

    @TaskPriority(10)
    @TaskRetry(maxAttempts = 5)
    @TaskLease(maxExecutionTime = "2h")
    static class Combined {}

    @TaskRetry(maxAttempts = 5)
    static class OnlyRetry {}
}
