package xyz.avasilev.taskcomm.task.internal;

import java.util.Map;
import java.util.Objects;

/**
 * Переопределения типа задачи из {@code yaml}: на уровне типа и по фазам
 * ({@code taskcomm.task.types.<тип>}, docs/rules/configuration.md). Фаза {@code yaml} покрывает
 * все методы фазы — у группового процессора раскрытие идёт в {@code expand} и {@code expandBatch}
 * (docs/adr/0118-attempt-settings-per-processor-method.md).
 *
 * @param type уровень типа
 * @param phases уровень фазы по ключу из {@code yaml} — {@code process}, {@code expand},
 *     {@code collect}; неизвестный ключ и приоритет на фазе — падение при разрешении настроек
 */
public record TypeOverrides(SettingsOverride type, Map<String, SettingsOverride> phases) {

    /** Тип в {@code yaml} не упомянут. */
    public static final TypeOverrides NONE = new TypeOverrides(SettingsOverride.NONE, Map.of());

    /**
     * Копирует фазы и проверяет, что уровень типа задан.
     *
     * @throws NullPointerException если уровень типа пуст
     */
    public TypeOverrides {
        Objects.requireNonNull(type, "type");
        phases = Map.copyOf(phases);
    }
}
