package xyz.avasilev.taskcomm.task.internal;

/**
 * Вид попытки — колонка {@code phase} сущностей задач и {@code tc_task_attempt}
 * (docs/adr/0069-parent-phase-column.md). Читается так же, как имя метода процессора.
 */
enum TaskPhase {

    /** Обработка: {@code process}. */
    PROCESS,

    /** Раскрытие: {@code expand} или {@code expandBatch}. */
    EXPAND,

    /** Сборка: {@code collect} или {@code collectBatch}. */
    COLLECT
}
