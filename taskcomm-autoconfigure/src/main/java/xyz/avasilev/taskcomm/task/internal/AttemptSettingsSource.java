package xyz.avasilev.taskcomm.task.internal;

/**
 * Откуда захват и завершение берут настройки попыток по типу задачи и фазе. Разрешение
 * по пяти уровням (docs/adr/0061-processor-configuration-annotations.md) — дело реализации;
 * захват знает только итог.
 */
@FunctionalInterface
interface AttemptSettingsSource {

    /**
     * Итоговые настройки.
     *
     * @param taskType тип задачи
     * @param phase фаза попытки
     * @return настройки попыток
     */
    AttemptSettings settings(String taskType, TaskPhase phase);

    /**
     * Одни настройки на все типы и фазы — глобальные значения подсистемы.
     *
     * @param settings глобальные настройки
     * @return источник, не различающий типы и фазы
     */
    static AttemptSettingsSource uniform(final AttemptSettings settings) {
        return (taskType, phase) -> settings;
    }
}
