package xyz.avasilev.taskcomm.task.internal;

import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Форма контракта процессора — колонка {@code tc_task.contract_shape}
 * (docs/adr/0026-contract-shape-marking.md). Пишется при первом захвате задачи вместе с фазой.
 */
enum ContractShape {

    /** {@code FlatTaskProcessor}: первая и единственная фаза — обработка. */
    FLAT(TaskPhase.PROCESS),

    /** {@code UnitTaskProcessor}: первая фаза — раскрытие в подзадачи. */
    UNIT(TaskPhase.EXPAND),

    /** {@code BatchTaskProcessor}: первая фаза — раскрытие в группы. */
    BATCH(TaskPhase.EXPAND);

    private final TaskPhase firstPhase;

    ContractShape(final TaskPhase firstPhase) {
        this.firstPhase = firstPhase;
    }

    /**
     * Форма контракта процессора. Разбор запечатанной иерархии без ветки {@code default}:
     * четвёртый контракт сломает компиляцию здесь (docs/adr/0007-processor-contracts.md).
     *
     * @param processor процессор
     * @return форма его контракта
     */
    static ContractShape of(final TaskProcessor<?> processor) {
        return switch (processor) {
            case FlatTaskProcessor<?, ?> _ -> FLAT;
            case UnitTaskProcessor<?, ?, ?, ?> _ -> UNIT;
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> _ -> BATCH;
        };
    }

    /**
     * Фаза первой попытки задачи этой формы.
     *
     * @return фаза первого захвата
     */
    TaskPhase firstPhase() {
        return this.firstPhase;
    }
}
