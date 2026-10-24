package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Разрешение настроек подсистемы задач по пяти уровням, от сильного к слабому: {@code yaml} типа
 * и фазы, {@code yaml} типа, аннотация на методе, аннотация на классе, глобальное значение
 * (docs/adr/0061-processor-configuration-annotations.md). Любое значение из {@code yaml} сильнее
 * любой аннотации — правило по источнику, а не по специфичности.
 *
 * <p>Ключ — тип и метод процессора (docs/adr/0118-attempt-settings-per-processor-method.md);
 * фаза {@code yaml} покрывает все методы фазы. Итог по каждому ключу печатается с уровнем каждого
 * значения: без печати пять уровней делают вопрос «почему у меня такой предел» вопросом без ответа.
 *
 * <p>Падение при старте: {@code heartbeat-interval >= duration} по каждому итоговому значению,
 * с типом, методом и уровнями в сообщении (docs/adr/0103-heartbeat-not-shorter-than-lease-fails.md);
 * остальные недопустимые значения аренды и повторов (docs/rules/configuration.md); приоритет
 * не больше нуля; приоритет на фазе {@code yaml} (docs/adr/0084-priority-only-per-type.md);
 * неизвестная фаза в {@code yaml}. Предупреждение: heartbeat больше трети аренды; аренда не длиннее
 * такта поллера типа; фаза {@code yaml}, которой у процессора нет.
 */
@Slf4j
final class TaskSettingsResolver {

    private static final String TYPES = "taskcomm.task.types.";

    private final AttemptSettings global;

    private final Map<String, TypeOverrides> yaml;

    private final PollerLayout pollers;

    private final Map<String, Map<TaskPhase, SettingsOverride>> phases = new HashMap<>();

    private final List<String> report = new ArrayList<>();

    TaskSettingsResolver(
            final AttemptSettings global, final Map<String, TypeOverrides> yaml, final PollerLayout pollers) {
        this.global = global;
        this.yaml = Map.copyOf(yaml);
        this.pollers = pollers;
    }

    TaskSettings resolve(final TaskProcessorRegistry registry, final ResultDiscard discard) {
        this.yaml.forEach(this::readPhases);
        final Map<TaskSettings.Key, AttemptSettings> attempts = new HashMap<>();
        final Map<String, Integer> priorities = new HashMap<>();
        for (final RegisteredProcessor processor : registry.all()) {
            final String type = processor.taskType();
            final SettingsOverride onClass = ProcessorSettingsAnnotations.onClass(processor.processorClass());
            final int priority = this.priority(type, onClass);
            priorities.put(type, priority);
            this.report.add("тип '" + type + "' (" + processor.shape() + "), приоритет " + priority);
            for (final ProcessorMethod method : ProcessorMethod.of(processor.shape())) {
                final SettingsOverride onMethod =
                        ProcessorSettingsAnnotations.onMethod(processor.processorClass(), method);
                attempts.put(new TaskSettings.Key(type, method), this.attempt(type, method, onMethod, onClass));
            }
            this.warnAboutUnusedPhases(processor);
        }
        for (final String type : this.yaml.keySet()) {
            if (registry.find(type).isEmpty()) {
                this.report.add("тип '" + type + "' — без процессора, только yaml");
                priorities.put(type, this.priority(type, SettingsOverride.NONE));
                for (final ProcessorMethod method : ProcessorMethod.values()) {
                    attempts.put(
                            new TaskSettings.Key(type, method),
                            this.attempt(type, method, SettingsOverride.NONE, SettingsOverride.NONE));
                }
            }
        }
        log.info(
                "Итоговые настройки задач по типам и методам процессоров, в скобках — уровень значения:\n  {}",
                String.join("\n  ", this.report));
        return new TaskSettings(this.global, attempts, priorities, this.heartbeatTick(attempts), this.pollers, discard);
    }

    private void readPhases(final String type, final TypeOverrides overrides) {
        final Map<TaskPhase, SettingsOverride> byPhase = new EnumMap<>(TaskPhase.class);
        overrides.phases().forEach((key, settings) -> {
            final TaskPhase phase;
            try {
                phase = TaskPhase.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException unknown) {
                throw new TaskcommConfigurationException(
                        TYPES + type + ".phases." + key + ": фазы '" + key + "' нет. Фазы — process, expand, collect.",
                        unknown);
            }
            if (settings.priority() != null) {
                throw new TaskcommConfigurationException(TYPES + type + ".phases." + key + ".priority: приоритет "
                        + "на фазе запрещён — он свойство задачи, а не попытки. Задайте его в " + TYPES + type
                        + ".priority (docs/adr/0084-priority-only-per-type.md).");
            }
            byPhase.put(phase, settings);
        });
        this.phases.put(type, byPhase);
    }

    private int priority(final String type, final SettingsOverride onClass) {
        final Integer fromYaml =
                this.yaml.getOrDefault(type, TypeOverrides.NONE).type().priority();
        if (fromYaml != null && fromYaml <= 0) {
            throw new TaskcommConfigurationException(TYPES + type + ".priority = " + fromYaml
                    + ", а должен быть больше нуля. Шкала по возрастанию: меньше значит важнее.");
        }
        if (onClass.priority() != null && onClass.priority() <= 0) {
            throw new TaskcommConfigurationException("Приоритет типа '" + type + "' в аннотации = " + onClass.priority()
                    + ", а должен быть больше нуля.");
        }
        if (fromYaml != null) {
            return fromYaml;
        }
        return onClass.priority() != null ? onClass.priority() : TaskSettings.DEFAULT_PRIORITY;
    }

    private AttemptSettings attempt(
            final String type,
            final ProcessorMethod method,
            final SettingsOverride onMethod,
            final SettingsOverride onClass) {
        final TypeOverrides typeOverrides = this.yaml.getOrDefault(type, TypeOverrides.NONE);
        final List<Layer> layers = List.of(
                new Layer(
                        SettingsLevel.YAML_PHASE,
                        this.phases.getOrDefault(type, Map.of()).getOrDefault(method.phase(), SettingsOverride.NONE)),
                new Layer(SettingsLevel.YAML_TYPE, typeOverrides.type()),
                new Layer(SettingsLevel.METHOD_ANNOTATION, onMethod),
                new Layer(SettingsLevel.CLASS_ANNOTATION, onClass));
        final String where = "тип задачи '" + type + "', метод " + method.methodName() + " (" + method.level() + ", "
                + method.phase() + ")";
        final LeaseSettings lease = this.lease(layers, where);
        final RetryPolicy retry = this.retry(layers, where);
        this.report.add("  " + method.methodName() + " (" + method.level() + ", " + method.phase() + "): "
                + this.describe(layers));
        this.warnAboutLease(type, lease, where);
        return new AttemptSettings(retry, lease);
    }

    private LeaseSettings lease(final List<Layer> layers, final String where) {
        final LeaseSettings defaults = this.global.lease();
        final Picked<Duration> duration = pick(layers, o -> o.lease().duration(), defaults.duration());
        final Picked<Duration> heartbeat =
                pick(layers, o -> o.lease().heartbeatInterval(), defaults.heartbeatInterval());
        final Picked<Duration> maxExecution =
                pick(layers, o -> o.lease().maxExecutionTime(), defaults.maxExecutionTime());
        if (heartbeat.value().compareTo(duration.value()) >= 0) {
            throw new TaskcommConfigurationException(where + ": heartbeat-interval = " + heartbeat.value() + " ("
                    + heartbeat.level().label() + ") не меньше duration = " + duration.value() + " ("
                    + duration.level().label() + "): аренда истекала бы раньше первого продления. "
                    + "Рекомендуемый интервал — не больше " + duration.value().dividedBy(3)
                    + " (docs/adr/0103-heartbeat-not-shorter-than-lease-fails.md).");
        }
        return build(() -> new LeaseSettings(duration.value(), heartbeat.value(), maxExecution.value()), where);
    }

    private RetryPolicy retry(final List<Layer> layers, final String where) {
        final RetryPolicy defaults = this.global.retry();
        return build(
                () -> new RetryPolicy(
                        pick(layers, o -> o.retry().maxAttempts(), defaults.maxAttempts())
                                .value(),
                        pick(layers, o -> o.retry().initialDelay(), defaults.initialDelay())
                                .value(),
                        pick(layers, o -> o.retry().multiplier(), defaults.multiplier())
                                .value(),
                        pick(layers, o -> o.retry().maxDelay(), defaults.maxDelay())
                                .value(),
                        pick(layers, o -> o.retry().jitter(), defaults.jitter()).value()),
                where);
    }

    private String describe(final List<Layer> layers) {
        final LeaseSettings lease = this.global.lease();
        final RetryPolicy retry = this.global.retry();
        return "lease.duration " + pick(layers, o -> o.lease().duration(), lease.duration())
                + ", lease.heartbeat-interval "
                + pick(layers, o -> o.lease().heartbeatInterval(), lease.heartbeatInterval())
                + ", lease.max-execution-time "
                + pick(layers, o -> o.lease().maxExecutionTime(), lease.maxExecutionTime())
                + "; retry.max-attempts " + pick(layers, o -> o.retry().maxAttempts(), retry.maxAttempts())
                + ", retry.initial-delay " + pick(layers, o -> o.retry().initialDelay(), retry.initialDelay())
                + ", retry.multiplier " + pick(layers, o -> o.retry().multiplier(), retry.multiplier())
                + ", retry.max-delay " + pick(layers, o -> o.retry().maxDelay(), retry.maxDelay())
                + ", retry.jitter " + pick(layers, o -> o.retry().jitter(), retry.jitter());
    }

    private void warnAboutLease(final String type, final LeaseSettings lease, final String where) {
        if (lease.heartbeatInterval().compareTo(lease.duration().dividedBy(3)) > 0) {
            log.warn(
                    "{}: heartbeat-interval = {} больше трети аренды {}. Два пропущенных такта — и аренда истечёт "
                            + "у живого воркера, работа выполнится дважды. Рекомендуемый интервал — {}.",
                    where,
                    lease.heartbeatInterval(),
                    lease.duration(),
                    lease.duration().dividedBy(3));
        }
        final Duration pollInterval = this.pollers.pollerOf(type).interval();
        if (lease.duration().compareTo(pollInterval) <= 0) {
            log.warn(
                    "{}: аренда {} не длиннее такта поллера {}: брошенная работа возвращается не раньше такта, "
                            + "а аренда истекает быстрее.",
                    where,
                    lease.duration(),
                    pollInterval);
        }
    }

    private void warnAboutUnusedPhases(final RegisteredProcessor processor) {
        final List<TaskPhase> used = ProcessorMethod.of(processor.shape()).stream()
                .map(ProcessorMethod::phase)
                .toList();
        this.phases.getOrDefault(processor.taskType(), Map.of()).keySet().stream()
                .filter(phase -> !used.contains(phase))
                .forEach(phase -> log.warn(
                        "{}{}.phases.{}: у процессора формы {} нет фазы {} — настройки фазы ни на что не действуют.",
                        TYPES,
                        processor.taskType(),
                        phase.name().toLowerCase(Locale.ROOT),
                        processor.shape(),
                        phase));
    }

    private Duration heartbeatTick(final Map<TaskSettings.Key, AttemptSettings> attempts) {
        return attempts.values().stream()
                .map(settings -> settings.lease().heartbeatInterval())
                .min(Duration::compareTo)
                .orElse(this.global.lease().heartbeatInterval());
    }

    private static <V> Picked<V> pick(
            final List<Layer> layers, final Function<SettingsOverride, V> value, final V globalValue) {
        for (final Layer layer : layers) {
            final V candidate = value.apply(layer.settings());
            if (candidate != null) {
                return new Picked<>(candidate, layer.level());
            }
        }
        return new Picked<>(globalValue, SettingsLevel.GLOBAL);
    }

    private static <T> T build(final Supplier<T> settings, final String where) {
        try {
            return settings.get();
        } catch (final TaskcommConfigurationException invalid) {
            throw new TaskcommConfigurationException(where + ": " + invalid.getMessage(), invalid);
        }
    }

    /**
     * Уровень разрешения со своими значениями.
     *
     * @param level уровень
     * @param settings значения уровня
     */
    private record Layer(SettingsLevel level, SettingsOverride settings) {}

    /**
     * Итоговое значение и уровень, с которого оно взято.
     *
     * @param value значение
     * @param level уровень
     * @param <V> тип значения
     */
    private record Picked<V>(V value, SettingsLevel level) {

        @Override
        public String toString() {
            return this.value + " (" + this.level.label() + ")";
        }
    }
}
