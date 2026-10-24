package xyz.avasilev.taskcomm.execution.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Дело — набор связанных задач и коммуникаций вокруг одного повода
 * (docs/adr/0038-execution-aggregate.md).
 *
 * <p>Пассивный агрегат: у дела нет статуса, счётчиков и прогресса, оно не завершается
 * и не отменяется. Состав дела читается сервисами подсистем по {@link #ref()}
 * (docs/adr/0110-execution-composition-reads-in-subsystems.md).
 */
public interface Execution {

    /**
     * Идентификатор дела.
     *
     * @return идентификатор, UUIDv7
     */
    UUID id();

    /**
     * Время создания дела по часам базы.
     *
     * @return время создания
     */
    Instant createdAt();

    /**
     * Ссылка на дело для методов создания и чтения состава.
     *
     * @return ссылка из идентификатора и времени создания
     */
    default ExecutionRef ref() {
        return new ExecutionRef(this.id(), this.createdAt());
    }
}
