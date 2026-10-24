package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Политика повторов одной фазы одного типа задачи: лимит попыток и экспоненциальный backoff
 * с обязательным джиттером (docs/adr/0009-retry-and-progress.md,
 * docs/adr/0060-retry-and-lease-configuration.md). Недопустимое значение не даёт собрать
 * политику — это проверки «падение при старте» из docs/rules/configuration.md.
 *
 * <p>Политика считает только <b>длительность</b> задержки; момент следующей попытки считает база,
 * {@code now() + :delay} (docs/adr/0079-database-clock.md).
 *
 * @param maxAttempts сколько попыток в цикле повторов, не меньше одной
 * @param initialDelay задержка после первой неуспешной попытки
 * @param multiplier во сколько раз растёт задержка с каждой следующей, не меньше единицы
 * @param maxDelay потолок задержки до джиттера
 * @param jitter доля случайного разброса задержки в обе стороны, в пределах {@code (0, 1]}
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay, double jitter) {

    /**
     * Проверяет значения.
     *
     * @throws TaskcommConfigurationException если с такими значениями стартовать нельзя
     */
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new TaskcommConfigurationException(
                    "max-attempts = " + maxAttempts + ", а должно быть не меньше 1: работа не выполнилась бы ни разу.");
        }
        if (initialDelay.isNegative() || maxDelay.isNegative()) {
            throw new TaskcommConfigurationException("initial-delay = " + initialDelay + ", max-delay = " + maxDelay
                    + ": задержка не бывает отрицательной.");
        }
        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new TaskcommConfigurationException(
                    "initial-delay = " + initialDelay + " больше max-delay = " + maxDelay + ".");
        }
        if (multiplier < 1) {
            throw new TaskcommConfigurationException(
                    "multiplier = " + multiplier + ", а должен быть не меньше 1: задержка не должна убывать.");
        }
        if (!(jitter > 0 && jitter <= 1)) {
            throw new TaskcommConfigurationException("jitter = " + jitter + ", а должен лежать в (0, 1]: без джиттера "
                    + "работа, упавшая разом, разом же и повторяется (docs/adr/0009-retry-and-progress.md).");
        }
    }

    /**
     * Исчерпан ли лимит.
     *
     * @param attempts сколько попыток уже сделано в цикле повторов, включая только что закрытую
     * @return {@code true}, если следующей попытки не будет
     */
    public boolean exhausted(final int attempts) {
        return attempts >= this.maxAttempts;
    }

    /**
     * Задержка перед следующей попыткой: {@code initialDelay × multiplier^(attempts − 1)},
     * ограниченная {@code maxDelay}, и разброс {@code ± jitter} от неё.
     *
     * @param attempts сколько попыток уже сделано в цикле повторов, не меньше одной
     * @param random источник случайных чисел в {@code [0, 1)}
     * @return задержка, не отрицательная
     */
    public Duration delay(final int attempts, final DoubleSupplier random) {
        final double base =
                Math.min(this.initialDelay.toMillis() * Math.pow(this.multiplier, Math.max(0, attempts - 1)), (double)
                        this.maxDelay.toMillis());
        final double spread = base * this.jitter * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.max(0, Math.round(base + spread)));
    }
}
