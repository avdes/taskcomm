package xyz.avasilev.taskcomm;

import java.io.Serial;

/**
 * Базовое исключение стартера. Всё, что стартер бросает сам, наследуется от него — потребитель
 * может отличить ошибки taskcomm от прочих одним {@code catch}.
 *
 * <p>Исключения, брошенные кодом процессора потребителя, этим типом не оборачиваются.
 */
public class TaskcommException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message объяснение для человека, читающего лог
     */
    public TaskcommException(final String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message объяснение для человека, читающего лог
     * @param cause исходная ошибка
     */
    public TaskcommException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
