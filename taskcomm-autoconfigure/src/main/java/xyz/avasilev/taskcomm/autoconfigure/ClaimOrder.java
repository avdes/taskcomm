package xyz.avasilev.taskcomm.autoconfigure;

/**
 * Форма захвата поллера — {@code taskcomm.task.poll.claim-order}
 * (docs/adr/0063-claim-ordering-and-pollers.md). Приоритет везде по возрастанию:
 * <b>меньше значит важнее</b>.
 */
public enum ClaimOrder {

    /**
     * Приоритет сдвигает дедлайн на {@code priority × deadline-step}: срочное идёт вперёд,
     * но только внутри окна, а голодание невозможно. Значение по умолчанию.
     */
    DEADLINE,

    /** Строгий приоритет, внутри одного приоритета — по времени готовности. */
    PRIORITY,

    /** Строгий приоритет, но доля захватов {@code fifo-share} идёт по чистому FIFO, мимо него. */
    QUOTA
}
