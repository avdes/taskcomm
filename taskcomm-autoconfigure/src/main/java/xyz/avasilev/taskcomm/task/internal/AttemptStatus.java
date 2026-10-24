package xyz.avasilev.taskcomm.task.internal;

/**
 * Исход попытки — колонка {@code tc_task_attempt.status} (docs/adr/0057-attempt-status-columns.md).
 * Пуст, пока попытка открыта. Не путать со статусом сущности.
 */
enum AttemptStatus {

    /** Метод процессора вернулся нормально. */
    SUCCEEDED,

    /** Метод процессора бросил исключение. */
    FAILED,

    /** Процессор вызвал {@code skip(reason)}. */
    SKIPPED,

    /** Аренда истекла, попытку закрыл переподхват (docs/adr/0067-expired-lease-closes-attempt.md). */
    LEASE_LOST
}
