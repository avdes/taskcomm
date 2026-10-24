package xyz.avasilev.taskcomm.execution.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ссылка на дело — неизменяемое значение, которое можно унести в другой поток, сохранить
 * и передать в метод создания позже (docs/adr/0056-execution-ref.md).
 *
 * <p>Явно переданная ссылка побеждает неявную привязку потока: сущность попадает в указанное дело,
 * в каком бы потоке её ни создали. Срока годности у ссылки нет — дело не завершается, а старые
 * партиции остаются записываемыми. Ссылка на дело, удалённое вместе с партицией, даёт громкую
 * ошибку при создании, а не тихую потерю связи.
 *
 * @param id идентификатор дела
 * @param createdAt время создания дела по часам базы — ключ партиционирования, без него строку
 *     в таблицу дела не вставить
 */
public record ExecutionRef(UUID id, Instant createdAt) {

    /**
     * Проверяет, что оба поля заданы.
     *
     * @throws NullPointerException если поле пустое
     */
    public ExecutionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
