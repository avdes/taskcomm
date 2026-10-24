package xyz.avasilev.taskcomm.task.internal;

/**
 * Захваченная работа — открытая попытка под арендой этого экземпляра и сущность, над которой
 * она идёт.
 *
 * @param lease аренда попытки
 * @param target сущность попытки
 * @param taskType тип задачи
 * @param phase фаза попытки
 * @param history номер попытки и признак повтора
 * @param entity снимок сущности на момент захвата
 */
record ClaimedAttempt(
        AttemptLease lease,
        TaskEntityRef target,
        String taskType,
        TaskPhase phase,
        AttemptHistory history,
        ClaimedEntity entity) {

    /**
     * Метод процессора, которому принадлежит попытка.
     *
     * @return метод
     */
    ProcessorMethod method() {
        return ProcessorMethod.of(this.target.level(), this.phase);
    }
}
