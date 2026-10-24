package xyz.avasilev.taskcomm.task.internal;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ReflectionUtils;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.TaskProcessorSettings;

/**
 * Чтение аннотаций настроек с процессора: {@link TaskProcessorSettings} и сокращений
 * {@code @TaskPriority}, {@code @TaskLease}, {@code @TaskRetry}, слитых через {@link MergedAnnotations}
 * (docs/adr/0113-task-processor-settings-attributes.md).
 *
 * <p>Несколько аннотаций на одном месте складываются; один атрибут, заданный дважды, — падение при
 * старте. Приоритет на методе — тоже падение: он свойство сущности, а не попытки
 * (docs/adr/0084-priority-only-per-type.md). Ищутся аннотации на самом классе процессора и на его
 * методах, без обхода предков.
 */
final class ProcessorSettingsAnnotations {

    private ProcessorSettingsAnnotations() {}

    /**
     * Аннотации на классе процессора — уровень 4.
     *
     * @param processorClass класс процессора без прокси
     * @return заданные настройки
     * @throws TaskcommConfigurationException если атрибут задан дважды или длительность не разбирается
     */
    static SettingsOverride onClass(final Class<?> processorClass) {
        return read(processorClass, "класс " + processorClass.getName());
    }

    /**
     * Аннотации на методе процессора — уровень 3.
     *
     * @param processorClass класс процессора без прокси
     * @param method метод
     * @return заданные настройки; метода на классе нет — ничего не задано
     * @throws TaskcommConfigurationException если атрибут задан дважды, длительность не разбирается
     *     или на методе задан приоритет
     */
    static SettingsOverride onMethod(final Class<?> processorClass, final ProcessorMethod method) {
        final Method found = ReflectionUtils.findMethod(processorClass, method.methodName(), method.parameterTypes());
        if (found == null) {
            return SettingsOverride.NONE;
        }
        final String place = "метод " + processorClass.getName() + "." + method.methodName();
        final SettingsOverride settings = read(BridgeMethodResolver.findBridgedMethod(found), place);
        if (settings.priority() != null) {
            throw new TaskcommConfigurationException("Приоритет на методе запрещён (" + place + "): он свойство "
                    + "задачи, а не попытки, и на методе молча ничего не делал бы. Перенесите его на класс "
                    + "процессора (docs/adr/0084-priority-only-per-type.md).");
        }
        return settings;
    }

    private static SettingsOverride read(final AnnotatedElement element, final String place) {
        final Attributes attributes = new Attributes(place);
        MergedAnnotations.from(element, SearchStrategy.DIRECT).stream(TaskProcessorSettings.class)
                .forEach(attributes::add);
        return attributes.toOverride();
    }

    /** Заданные атрибуты всех аннотаций одного места с их источниками. */
    private static final class Attributes {

        private final String place;

        private final Map<String, Object> values = new HashMap<>();

        private final Map<String, String> sources = new HashMap<>();

        Attributes(final String place) {
            this.place = place;
        }

        void add(final MergedAnnotation<TaskProcessorSettings> annotation) {
            final String source = "@" + annotation.getRoot().getType().getSimpleName();
            this.put("priority", annotation.getInt("priority"), source);
            this.put("leaseDuration", annotation.getString("leaseDuration"), source);
            this.put("heartbeatInterval", annotation.getString("heartbeatInterval"), source);
            this.put("maxExecutionTime", annotation.getString("maxExecutionTime"), source);
            this.put("maxAttempts", annotation.getInt("maxAttempts"), source);
            this.put("initialDelay", annotation.getString("initialDelay"), source);
            this.put("multiplier", annotation.getDouble("multiplier"), source);
            this.put("maxDelay", annotation.getString("maxDelay"), source);
            this.put("jitter", annotation.getDouble("jitter"), source);
        }

        private void put(final String attribute, final Object value, final String source) {
            if (isUnset(value)) {
                return;
            }
            final String previous = this.sources.putIfAbsent(attribute, source);
            if (previous != null) {
                throw new TaskcommConfigurationException("Атрибут " + attribute + " задан дважды (" + this.place
                        + "): в " + previous + " и в " + source + ". Оставьте одно значение "
                        + "(docs/adr/0113-task-processor-settings-attributes.md).");
            }
            this.values.put(attribute, value);
        }

        SettingsOverride toOverride() {
            return new SettingsOverride(
                    (Integer) this.values.get("priority"),
                    new LeaseOverride(
                            this.duration("leaseDuration"),
                            this.duration("heartbeatInterval"),
                            this.duration("maxExecutionTime")),
                    new RetryOverride(
                            (Integer) this.values.get("maxAttempts"),
                            this.duration("initialDelay"),
                            (Double) this.values.get("multiplier"),
                            this.duration("maxDelay"),
                            (Double) this.values.get("jitter")));
        }

        private Duration duration(final String attribute) {
            final String text = (String) this.values.get(attribute);
            if (text == null) {
                return null;
            }
            try {
                return DurationStyle.detectAndParse(text);
            } catch (final IllegalArgumentException invalid) {
                throw new TaskcommConfigurationException(
                        "Атрибут " + attribute + " = \"" + text + "\" (" + this.place + ") — не длительность. "
                                + "Формат — как у Duration в Spring Boot: \"30s\", \"2h\".",
                        invalid);
            }
        }

        private static boolean isUnset(final Object value) {
            return switch (value) {
                case final Integer number -> number == 0;
                case final Double number -> number == 0;
                case final String text -> text.isEmpty();
                default -> throw new IllegalStateException("Неожиданный атрибут " + value);
            };
        }
    }
}
