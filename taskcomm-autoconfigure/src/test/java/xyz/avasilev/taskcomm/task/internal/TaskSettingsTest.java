package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.CollectedBatch;
import xyz.avasilev.taskcomm.task.api.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.Emitter;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskLease;
import xyz.avasilev.taskcomm.task.api.TaskPriority;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskProcessorSettings;
import xyz.avasilev.taskcomm.task.api.TaskRetry;
import xyz.avasilev.taskcomm.task.api.TaskUnit;

/**
 * Разрешение настроек по пяти уровням (docs/adr/0061-processor-configuration-annotations.md), ключ —
 * метод процессора (docs/adr/0118-attempt-settings-per-processor-method.md), и проверки при старте.
 * Каждая ступень порядка — своим тестом.
 */
class TaskSettingsTest {

    private static final AttemptSettings GLOBAL = new AttemptSettings(
            new RetryPolicy(3, Duration.ofSeconds(5), 2.0, Duration.ofHours(1), 0.2),
            new LeaseSettings(Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofMinutes(30)));

    private static final PollerLayout LAYOUT = TaskTestSettings.layout();

    @Test
    void globalValueIsLevelFive() {
        final TaskSettings settings = resolve(Map.of(), new PlainFlat());

        assertThat(settings.settings("plain", ProcessorMethod.PROCESS_TASK)).isEqualTo(GLOBAL);
        assertThat(settings.priority("plain")).isEqualTo(TaskSettings.DEFAULT_PRIORITY);
    }

    @Test
    void classAnnotationIsLevelFourAndGivesPriority() {
        final TaskSettings settings = resolve(Map.of(), new AnnotatedFlat());

        assertThat(maxAttempts(settings, "annotated", ProcessorMethod.PROCESS_TASK))
                .isEqualTo(5);
        assertThat(settings.priority("annotated")).isEqualTo(10);
    }

    @Test
    void methodAnnotationIsLevelThreeAndBeatsClass() {
        final TaskSettings settings = resolve(Map.of(), new AnnotatedFlat());

        assertThat(settings.settings("annotated", ProcessorMethod.PROCESS_TASK)
                        .lease()
                        .maxExecutionTime())
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void yamlTypeIsLevelTwoAndBeatsAnyAnnotation() {
        final TaskSettings settings = resolve(
                Map.of(
                        "annotated",
                        new TypeOverrides(
                                new SettingsOverride(
                                        50,
                                        new LeaseOverride(null, null, Duration.ofHours(4)),
                                        new RetryOverride(7, null, null, null, null)),
                                Map.of())),
                new AnnotatedFlat());

        assertThat(maxAttempts(settings, "annotated", ProcessorMethod.PROCESS_TASK))
                .isEqualTo(7);
        assertThat(settings.settings("annotated", ProcessorMethod.PROCESS_TASK)
                        .lease()
                        .maxExecutionTime())
                .isEqualTo(Duration.ofHours(4));
        assertThat(settings.priority("annotated")).isEqualTo(50);
    }

    @Test
    void yamlPhaseIsLevelOneAndCoversBothMethodsOfPhase() {
        final TaskSettings settings = resolve(
                Map.of(
                        "catalog",
                        new TypeOverrides(
                                new SettingsOverride(
                                        null, LeaseOverride.NONE, new RetryOverride(4, null, null, null, null)),
                                Map.of(
                                        "expand",
                                        new SettingsOverride(
                                                null,
                                                LeaseOverride.NONE,
                                                new RetryOverride(9, null, null, null, null))))),
                new Catalog());

        assertThat(maxAttempts(settings, "catalog", ProcessorMethod.EXPAND)).isEqualTo(9);
        assertThat(maxAttempts(settings, "catalog", ProcessorMethod.EXPAND_BATCH))
                .isEqualTo(9);
        assertThat(maxAttempts(settings, "catalog", ProcessorMethod.PROCESS_UNIT))
                .isEqualTo(4);
    }

    @Test
    void methodAnnotationActsOnlyOnItsMethodWithinPhase() {
        final TaskSettings settings = resolve(Map.of(), new Catalog());

        assertThat(settings.settings("catalog", ProcessorMethod.EXPAND_BATCH)
                        .lease()
                        .maxExecutionTime())
                .isEqualTo(Duration.ofHours(2));
        assertThat(settings.settings("catalog", ProcessorMethod.EXPAND).lease().maxExecutionTime())
                .isEqualTo(GLOBAL.lease().maxExecutionTime());
    }

    @Test
    void typeWithoutProcessorGetsYamlAndGlobal() {
        final TaskSettings settings = resolve(Map.of(
                "orphan",
                new TypeOverrides(
                        new SettingsOverride(null, LeaseOverride.NONE, new RetryOverride(2, null, null, null, null)),
                        Map.of())));

        assertThat(maxAttempts(settings, "orphan", ProcessorMethod.PROCESS_UNIT))
                .isEqualTo(2);
        assertThat(settings.settings("unknown", ProcessorMethod.PROCESS_TASK)).isEqualTo(GLOBAL);
    }

    @Test
    void heartbeatTickIsShortestResolvedInterval() {
        final TaskSettings settings = resolve(Map.of(), new PlainFlat(), new ShortLease());

        assertThat(settings.heartbeatTick()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void failsWhenHeartbeatIsNotShorterThanResolvedLeaseNamingTypeMethodAndLevels() {
        assertThatThrownBy(() -> resolve(
                        Map.of(
                                "plain",
                                new TypeOverrides(
                                        SettingsOverride.NONE,
                                        Map.of(
                                                "process",
                                                new SettingsOverride(
                                                        null,
                                                        new LeaseOverride(Duration.ofSeconds(15), null, null),
                                                        RetryOverride.NONE)))),
                        new PlainFlat()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("'plain'")
                .hasMessageContaining("process (TASK, PROCESS)")
                .hasMessageContaining("yaml, тип и фаза")
                .hasMessageContaining("глобальное значение")
                .hasMessageContaining("PT5S");
    }

    @Test
    void failsOnPriorityInYamlPhase() {
        assertThatThrownBy(() -> resolve(
                        Map.of(
                                "plain",
                                new TypeOverrides(
                                        SettingsOverride.NONE,
                                        Map.of(
                                                "process",
                                                new SettingsOverride(5, LeaseOverride.NONE, RetryOverride.NONE)))),
                        new PlainFlat()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("taskcomm.task.types.plain.phases.process.priority");
    }

    @Test
    void failsOnUnknownYamlPhase() {
        assertThatThrownBy(() -> resolve(
                        Map.of(
                                "plain",
                                new TypeOverrides(SettingsOverride.NONE, Map.of("procces", SettingsOverride.NONE))),
                        new PlainFlat()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("phases.procces");
    }

    @Test
    void failsOnNonPositiveYamlPriority() {
        assertThatThrownBy(() -> resolve(
                        Map.of(
                                "plain",
                                new TypeOverrides(
                                        new SettingsOverride(0, LeaseOverride.NONE, RetryOverride.NONE), Map.of())),
                        new PlainFlat()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("taskcomm.task.types.plain.priority");
    }

    @Test
    void failsOnPriorityInMethodAnnotation() {
        assertThatThrownBy(() -> resolve(Map.of(), new PriorityOnMethod()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("Приоритет на методе");
    }

    @Test
    void failsWhenAttributeIsSetTwiceInOnePlace() {
        assertThatThrownBy(() -> resolve(Map.of(), new TwiceOnClass()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("maxAttempts задан дважды");
    }

    @Test
    void failsOnInvalidAnnotationValueNamingType() {
        assertThatThrownBy(() -> resolve(Map.of(), new TooMuchJitter()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("'too-much-jitter'")
                .hasMessageContaining("jitter");
    }

    @Test
    void registryFailsOnTwoProcessorsOfOneType() {
        assertThatThrownBy(() -> new TaskProcessorRegistry(List.of(new PlainFlat(), new PlainFlat())))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("'plain'");
    }

    @Test
    void registryResolvesPayloadAndResultTypesFromGenerics() {
        final RegisteredProcessor catalog = new TaskProcessorRegistry(List.of(new Catalog()))
                .find("catalog")
                .orElseThrow();

        assertThat(catalog.shape()).isEqualTo(ContractShape.BATCH);
        assertThat(catalog.types().task().resolve()).isEqualTo(String.class);
        assertThat(catalog.types().batch().resolve()).isEqualTo(Integer.class);
        assertThat(catalog.types().unit().resolve()).isEqualTo(Long.class);
        assertThat(catalog.types().taskResult().resolve()).isEqualTo(Void.class);
    }

    private static TaskSettings resolve(final Map<String, TypeOverrides> yaml, final TaskProcessor<?>... processors) {
        return TaskSettings.resolve(GLOBAL, yaml, new TaskProcessorRegistry(List.of(processors)), LAYOUT);
    }

    private static int maxAttempts(final TaskSettings settings, final String type, final ProcessorMethod method) {
        return settings.settings(type, method).retry().maxAttempts();
    }

    /** Без аннотаций. */
    static class PlainFlat implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "plain";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Приоритет и повторы на классе, предел на методе. */
    @TaskPriority(10)
    @TaskRetry(maxAttempts = 5)
    static class AnnotatedFlat implements FlatTaskProcessor<String, String> {

        @Override
        public String taskType() {
            return "annotated";
        }

        @Override
        @TaskLease(maxExecutionTime = "2h")
        public String process(final Task<String> task, final ProcessingContext context) {
            return task.payload();
        }
    }

    /** Короткая аренда на классе. */
    @TaskLease(duration = "10s", heartbeatInterval = "3s")
    static class ShortLease implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "short";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Приоритет на методе — запрещён. */
    static class PriorityOnMethod implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "priority-on-method";
        }

        @Override
        @TaskProcessorSettings(priority = 5)
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Один атрибут в двух аннотациях на классе. */
    @TaskRetry(maxAttempts = 5)
    @TaskProcessorSettings(maxAttempts = 6)
    static class TwiceOnClass implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "twice";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Джиттер больше единицы — недопустим. */
    @TaskRetry(jitter = 1.5)
    static class TooMuchJitter implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "too-much-jitter";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Процессор с группами: два метода фазы раскрытия, предел только на {@code expandBatch}. */
    static class Catalog implements BatchTaskProcessor<String, Integer, Long, Long, Long, Void> {

        @Override
        public String taskType() {
            return "catalog";
        }

        @Override
        public void expand(final Task<String> task, final Emitter<Integer> batches, final ProcessingContext context) {
            // группы не нужны тесту настроек
        }

        @Override
        @TaskLease(maxExecutionTime = "2h")
        public void expandBatch(
                final TaskBatch<Integer> batch, final Emitter<Long> units, final ProcessingContext context) {
            // подзадачи не нужны тесту настроек
        }

        @Override
        public Long process(final TaskUnit<Long> unit, final ProcessingContext context) {
            return unit.payload();
        }

        @Override
        public Long collectBatch(
                final TaskBatch<Integer> batch,
                final Stream<CollectedUnit<Long, Long>> units,
                final ProcessingContext context) {
            return 0L;
        }

        @Override
        public Void collect(
                final Task<String> task,
                final Stream<CollectedBatch<Integer, Long>> batches,
                final ProcessingContext context) {
            return null;
        }
    }
}
