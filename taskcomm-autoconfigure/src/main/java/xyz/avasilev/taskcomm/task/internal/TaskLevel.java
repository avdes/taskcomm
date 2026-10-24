package xyz.avasilev.taskcomm.task.internal;

/**
 * Уровень сущности задач — значение колонок {@code tc_task_attempt.level}
 * и {@code tc_task_result.level}. Значения в базе пишутся ровно как здесь.
 */
enum TaskLevel {

    /** Задача — строка {@code tc_task}. */
    TASK("tc_task"),

    /** Группа — строка {@code tc_task_batch}. */
    BATCH("tc_task_batch"),

    /** Подзадача — строка {@code tc_task_unit}. */
    UNIT("tc_task_unit");

    private final String table;

    TaskLevel(final String table) {
        this.table = table;
    }

    /**
     * Таблица сущностей этого уровня.
     *
     * @return имя таблицы
     */
    String table() {
        return this.table;
    }
}
