package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Когда удаляются результаты детей после успешной сборки — {@code taskcomm.results.discard}
 * (docs/adr/0058-task-result-storage.md).
 *
 * @param mode режим
 * @param after через сколько после сборки удалять в режиме {@link Mode#AFTER}
 */
public record ResultDiscard(Mode mode, Duration after) {

    /**
     * Проверяет срок.
     *
     * @param mode режим
     * @param after срок удаления
     * @throws TaskcommConfigurationException если режим или срок не заданы или срок отрицательный
     */
    public ResultDiscard {
        if (mode == null || after == null || after.isNegative()) {
            throw new TaskcommConfigurationException(
                    "Удаление результатов детей: режим обязателен, срок не может быть отрицательным: " + after);
        }
    }

    /**
     * Сколько ждать после сборки, прежде чем удалять.
     *
     * @return ноль у {@link Mode#IMMEDIATELY}, срок у {@link Mode#AFTER}
     */
    Duration delay() {
        return this.mode == Mode.AFTER ? this.after : Duration.ZERO;
    }

    /** Режим удаления результатов детей. */
    public enum Mode {

        /** Не удалять: результаты живут до дропа партиции. */
        NEVER,

        /** Сразу после успешной сборки. */
        IMMEDIATELY,

        /** Через заданный срок после успешной сборки. */
        AFTER
    }
}
