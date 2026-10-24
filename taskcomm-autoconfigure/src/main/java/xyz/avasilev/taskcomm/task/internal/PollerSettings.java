package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Итоговые настройки одного поллера задач (docs/adr/0063-claim-ordering-and-pollers.md).
 * Недопустимое значение не даёт собрать настройки — проверки «падение при старте».
 *
 * @param name имя поллера: {@code main} у основного, ключ {@code taskcomm.task.pollers} у выделенного
 * @param types типы задач выделенного поллера; у основного пусто — он берёт все, кроме выделенных
 * @param interval пауза между захватами при пустой очереди
 * @param batchSize верхняя граница одного захвата
 * @param workers размер пула воркеров
 * @param form форма захвата
 * @param deadlineStep шаг дедлайна {@code K} в {@code claim_order_at = next_attempt_at + priority × K}
 * @param fifoShare доля захватов по чистому FIFO при форме {@code quota}, в пределах {@code (0, 1)}
 */
public record PollerSettings(
        String name,
        Set<String> types,
        Duration interval,
        int batchSize,
        int workers,
        ClaimForm form,
        Duration deadlineStep,
        double fifoShare) {

    /** Имя основного поллера. */
    public static final String MAIN = "main";

    /**
     * Проверяет значения и копирует набор типов.
     *
     * @throws TaskcommConfigurationException если с такими значениями стартовать нельзя
     */
    public PollerSettings {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(form, "form");
        types = Set.copyOf(types);
        if (MAIN.equals(name) != types.isEmpty()) {
            throw new TaskcommConfigurationException("Поллер задач '" + name + "': набор типов задаётся у каждого "
                    + "выделенного поллера и только у них; основной берёт все типы, кроме выделенных.");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new TaskcommConfigurationException(
                    "Поллер задач '" + name + "': interval = " + interval + ", а должен быть положительным.");
        }
        if (batchSize < 1 || workers < 1) {
            throw new TaskcommConfigurationException("Поллер задач '" + name + "': batch-size = " + batchSize
                    + ", workers = " + workers + " — оба должны быть не меньше 1.");
        }
        if (deadlineStep.isNegative() || deadlineStep.isZero()) {
            throw new TaskcommConfigurationException(
                    "Поллер задач '" + name + "': deadline-step = " + deadlineStep + ", а должен быть положительным.");
        }
        if (!(fifoShare > 0 && fifoShare < 1)) {
            throw new TaskcommConfigurationException("Поллер задач '" + name + "': fifo-share = " + fifoShare
                    + ", а должна лежать в (0, 1). Чистый строгий приоритет — форма priority.");
        }
    }
}
