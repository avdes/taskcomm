package xyz.avasilev.taskcomm.task.internal;

/**
 * Уровень, с которого взято итоговое значение настройки, — от сильного к слабому
 * (docs/adr/0061-processor-configuration-annotations.md). Печатается при старте рядом со значением.
 */
enum SettingsLevel {

    /** {@code yaml}: тип и фаза. */
    YAML_PHASE("yaml, тип и фаза"),

    /** {@code yaml}: тип. */
    YAML_TYPE("yaml, тип"),

    /** Аннотация на методе процессора. */
    METHOD_ANNOTATION("аннотация на методе"),

    /** Аннотация на классе процессора. */
    CLASS_ANNOTATION("аннотация на классе"),

    /** Глобальное значение по умолчанию подсистемы. */
    GLOBAL("глобальное значение");

    private final String label;

    SettingsLevel(final String label) {
        this.label = label;
    }

    /**
     * Название уровня для печати.
     *
     * @return название
     */
    String label() {
        return this.label;
    }
}
