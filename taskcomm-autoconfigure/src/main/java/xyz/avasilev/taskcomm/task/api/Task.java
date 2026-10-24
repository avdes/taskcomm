package xyz.avasilev.taskcomm.task.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Задача: работа над множеством бизнес-объектов потребителя. Корень иерархии
 * задача → группы → подзадачи.
 *
 * <p>Интерфейс, а не запись: новое поле появляется новым методом и не ломает совместимость
 * (docs/adr/0111-task-public-api-shape.md). Реализует его стартер; потребитель задачи не собирает.
 * Значения — снимок на момент чтения: статус меняется, а объект — нет.
 *
 * @param <T> параметры задачи — например, период и список поставщиков; не один бизнес-объект
 */
public interface Task<T> {

    /**
     * Идентификатор задачи.
     *
     * @return идентификатор, UUIDv7
     */
    UUID id();

    /**
     * Дело, которому принадлежит задача.
     *
     * @return ссылка на дело
     */
    ExecutionRef executionRef();

    /**
     * Тип задачи — по нему ищется процессор.
     *
     * @return тип задачи
     */
    String taskType();

    /**
     * Параметры задачи.
     *
     * @return нагрузка, восстановленная в тип из дженерика процессора
     */
    T payload();

    /**
     * Приоритет. Шкала по возрастанию: <b>меньше значит важнее</b>, единица важнее сотни.
     *
     * @return приоритет, больше нуля
     */
    int priority();

    /**
     * Статус на момент чтения.
     *
     * @return статус задачи
     */
    TaskStatus status();

    /**
     * Причина терминального статуса самой задачи — для {@code SKIPPED} и {@code CANCELLED} и после
     * неповторяемого неуспеха.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Время создания задачи по часам базы. Может быть позже создания дела.
     *
     * @return время создания
     */
    Instant createdAt();

    /**
     * Кем создана задача внутри дела. Пусто у задачи, поставленной кодом потребителя вне процессора,
     * и у созданной в другом деле — это норма (docs/adr/0064-entity-origin-links.md).
     *
     * @return указатель на создателя или пусто
     */
    Optional<Origin> origin();
}
