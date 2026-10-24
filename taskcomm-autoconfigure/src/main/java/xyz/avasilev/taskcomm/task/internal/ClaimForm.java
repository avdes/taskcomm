package xyz.avasilev.taskcomm.task.internal;

/**
 * Форма захвата поллера — порядок, в котором он выбирает готовую работу
 * (docs/adr/0063-claim-ordering-and-pollers.md). У каждой свой частичный индекс.
 */
public enum ClaimForm {

    /** По {@code claim_order_at}: приоритет сдвигает дедлайн, голодание невозможно. */
    DEADLINE,

    /** По {@code priority, next_attempt_at}: строгий приоритет. */
    PRIORITY,

    /**
     * Строгий приоритет, но доля захватов — по чистому FIFO
     * (docs/adr/0115-quota-fifo-share-by-lottery.md).
     */
    QUOTA
}
