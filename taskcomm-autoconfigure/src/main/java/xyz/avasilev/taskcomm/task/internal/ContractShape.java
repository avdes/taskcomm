package xyz.avasilev.taskcomm.task.internal;

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
     * Фаза первой попытки задачи этой формы.
     *
     * @return фаза первого захвата
     */
    TaskPhase firstPhase() {
        return this.firstPhase;
    }
}
