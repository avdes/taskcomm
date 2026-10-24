package xyz.avasilev.taskcomm.task.internal;

import org.springframework.core.ResolvableType;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Java-типы нагрузок и результатов процессора, восстановленные из его дженерика
 * (docs/adr/0012-payload-storage.md). FQCN в базе не хранится: тип знает только процессор.
 * Буквы — как в контракте (docs/adr/0059-collect-contract.md); у уровней, которых у контракта нет,
 * поле пустое.
 *
 * @param task {@code T} — параметры задачи
 * @param batch {@code B} — параметры группы
 * @param unit {@code U} — бизнес-объект подзадачи
 * @param unitResult {@code RU} — результат подзадачи
 * @param batchResult {@code RB} — результат группы
 * @param taskResult {@code RT} — результат задачи
 */
record ProcessorTypes(
        ResolvableType task,
        ResolvableType batch,
        ResolvableType unit,
        ResolvableType unitResult,
        ResolvableType batchResult,
        ResolvableType taskResult) {

    /**
     * Типы процессора этой формы. Все параметры обязаны быть известны по классу: иначе нагрузку
     * не во что разобрать, и лучше узнать это при старте.
     *
     * @param processorClass класс процессора без прокси
     * @param shape форма контракта
     * @param taskType тип задачи — для сообщения
     * @return типы
     * @throws TaskcommConfigurationException если параметр типа не выводится из класса
     */
    static ProcessorTypes of(final Class<?> processorClass, final ContractShape shape, final String taskType) {
        final ResolvableType self = ResolvableType.forClass(processorClass);
        return switch (shape) {
            case FLAT -> {
                final ResolvableType flat = self.as(FlatTaskProcessor.class);
                yield new ProcessorTypes(
                        resolved(flat, 0, taskType, "T"), null, null, null, null, resolved(flat, 1, taskType, "RT"));
            }
            case UNIT -> {
                final ResolvableType unit = self.as(UnitTaskProcessor.class);
                yield new ProcessorTypes(
                        resolved(unit, 0, taskType, "T"),
                        null,
                        resolved(unit, 1, taskType, "U"),
                        resolved(unit, 2, taskType, "RU"),
                        null,
                        resolved(unit, 3, taskType, "RT"));
            }
            case BATCH -> {
                final ResolvableType batch = self.as(BatchTaskProcessor.class);
                yield new ProcessorTypes(
                        resolved(batch, 0, taskType, "T"),
                        resolved(batch, 1, taskType, "B"),
                        resolved(batch, 2, taskType, "U"),
                        resolved(batch, 3, taskType, "RU"),
                        resolved(batch, 4, taskType, "RB"),
                        resolved(batch, 5, taskType, "RT"));
            }
        };
    }

    private static ResolvableType resolved(
            final ResolvableType contract, final int index, final String taskType, final String letter) {
        final ResolvableType generic = contract.getGeneric(index);
        if (generic.resolve() == null) {
            throw new TaskcommConfigurationException("Процессор типа задачи '" + taskType + "': параметр " + letter
                    + " не выводится из класса процессора. Нагрузку и результат не во что разобрать — укажите "
                    + "конкретные типы в объявлении класса (docs/adr/0012-payload-storage.md).");
        }
        return generic;
    }
}
