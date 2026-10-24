package xyz.avasilev.taskcomm.task.internal;

import java.util.function.BooleanSupplier;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;

/**
 * Контекст одного вызова процессора. Все методы отвечают из памяти, без запроса к базе
 * (docs/adr/0112-attempt-number-in-processing-context.md, docs/adr/0062-non-retryable-failure.md).
 *
 * <p>{@link #shouldStop()} поднимают потеря аренды — её приносит поток heartbeat — и собственный
 * {@link #skip(String)} процессора. Отмену добавит механизм отмены
 * (docs/adr/0070-cancellation-on-any-entity.md).
 *
 * <p>Живёт один вызов; поля {@code volatile}, потому что потребитель вправе спросить
 * {@link #shouldStop()} из потока, который сам завёл внутри вызова.
 */
final class TaskProcessingContext implements ProcessingContext {

    private final ExecutionRef execution;

    private final AttemptHistory history;

    private final BooleanSupplier leaseLost;

    private volatile String skipReason;

    private volatile String doNotRetryReason;

    TaskProcessingContext(final ExecutionRef execution, final AttemptHistory history, final BooleanSupplier leaseLost) {
        this.execution = execution;
        this.history = history;
        this.leaseLost = leaseLost;
    }

    @Override
    public void skip(final String reason) {
        this.skipReason = requireReason(reason, "skip");
    }

    @Override
    public void doNotRetry(final String reason) {
        this.doNotRetryReason = requireReason(reason, "doNotRetry");
    }

    @Override
    public boolean shouldStop() {
        return this.skipReason != null || this.leaseLost.getAsBoolean();
    }

    @Override
    public ExecutionRef executionRef() {
        return this.execution;
    }

    @Override
    public int attemptNumber() {
        return this.history.number();
    }

    @Override
    public boolean isRetry() {
        return this.history.retry();
    }

    /**
     * Причина пропуска, если процессор его объявил.
     *
     * @return причина или {@code null}
     */
    String skipReason() {
        return this.skipReason;
    }

    /**
     * Причина запрета повтора, если процессор его объявил.
     *
     * @return причина или {@code null}
     */
    String doNotRetryReason() {
        return this.doNotRetryReason;
    }

    private static String requireReason(final String reason, final String method) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(method + "(reason): причина обязательна — она хранится в базе "
                    + "и объясняет исход тому, кто будет разбираться.");
        }
        return reason;
    }
}
