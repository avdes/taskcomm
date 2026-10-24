package xyz.avasilev.taskcomm.task.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Группа подзадач внутри задачи. Рождается только в раскрытии задачи и существует, чтобы сходить
 * во внешнюю систему один раз на всю группу. Собранные при раскрытии группы данные кладутся
 * в нагрузку каждой подзадачи, а не сюда (docs/adr/0022-batch-data-placement.md).
 *
 * <p>Интерфейс, а не запись (docs/adr/0111-task-public-api-shape.md). Значения — снимок на момент
 * чтения.
 *
 * @param <B> параметры группы — например, один поставщик
 */
public interface TaskBatch<B> {

    /**
     * Идентификатор группы.
     *
     * @return идентификатор, UUIDv7
     */
    UUID id();

    /**
     * Задача, к которой относится группа.
     *
     * @return идентификатор задачи
     */
    UUID taskId();

    /**
     * Дело задачи.
     *
     * @return ссылка на дело
     */
    ExecutionRef executionRef();

    /**
     * Тип задачи.
     *
     * @return тип задачи
     */
    String taskType();

    /**
     * Параметры группы.
     *
     * @return нагрузка, восстановленная в тип из дженерика процессора
     */
    B payload();

    /**
     * Приоритет, унаследованный от задачи. Шкала по возрастанию: <b>меньше значит важнее</b>.
     *
     * @return приоритет, больше нуля
     */
    int priority();

    /**
     * Статус на момент чтения.
     *
     * @return статус группы
     */
    TaskBatchStatus status();

    /**
     * Причина терминального статуса самой группы.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Время создания группы по часам базы.
     *
     * @return время создания
     */
    Instant createdAt();
}
