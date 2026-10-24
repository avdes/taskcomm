package xyz.avasilev.taskcomm.task.api;

import java.util.Objects;

/**
 * Чем кончился вызов отмены (docs/adr/0125-task-cancel-api.md). Отмена принята — на сущности
 * стоит отметка {@code cancel_requested_at}; что это значит для её статуса, говорит
 * {@link #reason()}: не начинавшаяся и ждущая сущность становится {@code CANCELLED} сразу,
 * работающая доигрывает текущую попытку и пишет настоящий исход
 * (docs/adr/0100-cancel-keeps-real-outcome.md). Отказ — отметка не ставилась.
 *
 * <p>Гонка «сущность завершилась между чтением и отменой» — обычный отказ, а не исключение.
 */
public sealed interface TaskCancelResult {

    /**
     * Принята ли отмена.
     *
     * @return {@code true}, если отметка поставлена этим вызовом
     */
    boolean accepted();

    /**
     * Причина исхода — для человека и для лога.
     *
     * @return что стало с сущностью или почему отмена отвергнута
     */
    String reason();

    /**
     * Отмена принята.
     *
     * @param reason что стало с сущностью: отменена сразу или доиграет текущую работу
     */
    record Accepted(String reason) implements TaskCancelResult {

        /**
         * Проверяет причину.
         *
         * @param reason причина
         * @throws NullPointerException если причина пуста
         */
        public Accepted {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean accepted() {
            return true;
        }
    }

    /**
     * Отмена отвергнута, отметка не ставилась.
     *
     * @param reason почему: сущность уже терминальна, уже отменена или не найдена
     */
    record Rejected(String reason) implements TaskCancelResult {

        /**
         * Проверяет причину.
         *
         * @param reason причина
         * @throws NullPointerException если причина пуста
         */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean accepted() {
            return false;
        }
    }
}
