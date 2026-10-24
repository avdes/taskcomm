package xyz.avasilev.taskcomm.task.internal;

/**
 * Откуда захват и завершение берут настройки попыток по типу задачи и методу процессора.
 * Разрешение по пяти уровням (docs/adr/0061-processor-configuration-annotations.md) — дело
 * реализации; захват знает только итог. Ключ — метод, а не фаза: у фазы бывает два метода
 * (docs/adr/0118-attempt-settings-per-processor-method.md).
 */
@FunctionalInterface
interface AttemptSettingsSource {

    /**
     * Итоговые настройки.
     *
     * @param taskType тип задачи
     * @param method метод процессора, которому принадлежит попытка
     * @return настройки попыток
     */
    AttemptSettings settings(String taskType, ProcessorMethod method);

    /**
     * Итоговые настройки попытки сущности этого уровня и фазы.
     *
     * @param taskType тип задачи
     * @param level уровень сущности
     * @param phase фаза попытки
     * @return настройки попыток
     */
    default AttemptSettings settings(final String taskType, final TaskLevel level, final TaskPhase phase) {
        return this.settings(taskType, ProcessorMethod.of(level, phase));
    }

    /**
     * Одни настройки на все типы и методы — глобальные значения подсистемы.
     *
     * @param settings глобальные настройки
     * @return источник, не различающий типы и методы
     */
    static AttemptSettingsSource uniform(final AttemptSettings settings) {
        return (taskType, method) -> settings;
    }
}
