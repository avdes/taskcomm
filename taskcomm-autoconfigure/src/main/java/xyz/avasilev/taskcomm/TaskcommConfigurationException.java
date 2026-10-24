package xyz.avasilev.taskcomm;

import java.io.Serial;

/**
 * Приложение не может стартовать с такими настройками или такой схемой БД: значение свойства
 * недопустимо, бин выбран неоднозначно, схема не соответствует версии стартера.
 *
 * <p>Бросается при старте, а не при первой задаче: ошибку конфигурации должен увидеть тот,
 * кто деплоит, а не тот, кто через неделю разбирает инцидент. Сообщение называет, что не так
 * и что сделать.
 */
public class TaskcommConfigurationException extends TaskcommException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message что не так и что сделать
     */
    public TaskcommConfigurationException(final String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message что не так и что сделать
     * @param cause исходная ошибка
     */
    public TaskcommConfigurationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
