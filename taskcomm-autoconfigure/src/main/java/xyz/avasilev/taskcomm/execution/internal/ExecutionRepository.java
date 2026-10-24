package xyz.avasilev.taskcomm.execution.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.execution.api.Execution;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Таблица {@code tc_execution}. Дело пассивно: здесь только заведение нового и поиск по
 * идентификатору (docs/adr/0038-execution-aggregate.md).
 *
 * <p>Методы не открывают транзакций: заведение дела обязано жить в одной транзакции с вставкой
 * сущности, и транзакцию держит тот, кто создаёт сущность
 * (docs/adr/0094-creation-always-in-transaction.md).
 */
@RequiredArgsConstructor
public class ExecutionRepository {

    private final JdbcClient jdbc;

    private final UuidV7 ids;

    /**
     * Заводит новое дело. Время создания — {@code now()} базы, и ссылка получает его через
     * {@code RETURNING}, а не вычисляет (docs/adr/0079-database-clock.md).
     *
     * @return ссылка на новое дело
     */
    public ExecutionRef create() {
        final UUID id = this.ids.next();
        final Instant createdAt = this.jdbc
                .sql("""
                        INSERT INTO tc_execution (id, created_at, updated_at)
                        VALUES (:id, now(), now())
                        RETURNING created_at
                        """)
                .param("id", id)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        return new ExecutionRef(id, createdAt);
    }

    /**
     * Ищет дело по идентификатору — по всем партициям: ключа партиционирования у вызывающего нет.
     *
     * @param id идентификатор дела
     * @return дело или пусто, если такого нет или его партиция удалена
     */
    public Optional<Execution> find(final UUID id) {
        return this.jdbc
                .sql("SELECT id, created_at FROM tc_execution WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> (Execution) new ExecutionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Снимок строки дела.
     *
     * @param id идентификатор дела
     * @param createdAt время создания по часам базы
     */
    private record ExecutionRow(UUID id, Instant createdAt) implements Execution {}
}
