package xyz.avasilev.taskcomm.task.internal;

import java.util.Objects;

/**
 * Итоговые настройки попыток одной фазы одного типа задачи: повторы и аренда.
 *
 * @param retry политика повторов
 * @param lease аренда
 */
public record AttemptSettings(RetryPolicy retry, LeaseSettings lease) {

    /**
     * Проверяет, что обе части заданы.
     *
     * @throws NullPointerException если часть пустая
     */
    public AttemptSettings {
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(lease, "lease");
    }
}
