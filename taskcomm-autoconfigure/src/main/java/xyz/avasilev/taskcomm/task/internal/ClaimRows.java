package xyz.avasilev.taskcomm.task.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;

/** Разбор колонок строки запроса захвата: моменты, указатель на создателя и родство ребёнка. */
final class ClaimRows {

    private ClaimRows() {}

    /**
     * Момент из колонки {@code timestamptz}: драйвер отдаёт {@link OffsetDateTime}.
     *
     * @param rs строка запроса
     * @param column колонка
     * @return момент
     * @throws SQLException если колонку не прочитать
     */
    static Instant instant(final ResultSet rs, final String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /**
     * Указатель на создателя (docs/adr/0064-entity-origin-links.md).
     *
     * @param rs строка запроса захвата
     * @return указатель или {@code null}, если сущность корневая или это не задача
     * @throws SQLException если колонку не прочитать
     */
    static Origin origin(final ResultSet rs) throws SQLException {
        final String type = rs.getString("origin_type");
        return type == null
                ? null
                : new Origin(
                        OriginType.valueOf(type),
                        rs.getObject("origin_id", UUID.class),
                        rs.getObject("origin_attempt_id", UUID.class),
                        OriginRelation.valueOf(rs.getString("origin_relation")));
    }

    /**
     * Родство ребёнка раскрытия (docs/adr/0047-children-belong-to-attempt.md).
     *
     * @param rs строка запроса захвата
     * @return родство или {@code null} у задачи
     * @throws SQLException если колонку не прочитать
     */
    static ClaimedEntity.Parent parent(final ResultSet rs) throws SQLException {
        final UUID attemptId = rs.getObject("parent_attempt_id", UUID.class);
        return attemptId == null
                ? null
                : new ClaimedEntity.Parent(
                        rs.getObject("parent_task_id", UUID.class),
                        rs.getObject("parent_batch_id", UUID.class),
                        attemptId);
    }
}
