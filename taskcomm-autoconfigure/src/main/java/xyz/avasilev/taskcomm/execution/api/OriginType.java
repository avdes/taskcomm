package xyz.avasilev.taskcomm.execution.api;

/**
 * Вид сущности-создателя в {@link Origin}. Значения совпадают с колонкой {@code origin_type}
 * и с уровнями попыток задач, дополненными коммуникацией (docs/adr/0064-entity-origin-links.md).
 */
public enum OriginType {

    /** Задача. */
    TASK,

    /** Группа подзадач. */
    BATCH,

    /** Подзадача. */
    UNIT,

    /** Коммуникация. */
    COMMUNICATION
}
