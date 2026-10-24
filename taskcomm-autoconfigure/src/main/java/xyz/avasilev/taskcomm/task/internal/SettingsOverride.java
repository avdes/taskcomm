package xyz.avasilev.taskcomm.task.internal;

import java.util.Objects;

/**
 * Всё, что задаёт один уровень разрешения настроек: приоритет, аренда, повторы
 * (docs/adr/0061-processor-configuration-annotations.md). Приоритет осмыслен только на уровне типа —
 * в {@code yaml} типа и в аннотации на классе (docs/adr/0084-priority-only-per-type.md).
 *
 * @param priority приоритет по умолчанию или {@code null}
 * @param lease аренда
 * @param retry повторы
 */
public record SettingsOverride(Integer priority, LeaseOverride lease, RetryOverride retry) {

    /** Ничего не задано. */
    public static final SettingsOverride NONE = new SettingsOverride(null, LeaseOverride.NONE, RetryOverride.NONE);

    /**
     * Проверяет, что группы настроек заданы, хотя бы пустыми.
     *
     * @throws NullPointerException если группа пуста
     */
    public SettingsOverride {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(retry, "retry");
    }
}
