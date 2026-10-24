package xyz.avasilev.taskcomm.task.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Флаги отмены сущности и её предков (docs/adr/0070-cancellation-on-any-entity.md). Флаг стоит
 * только там, где вызвали отмену, вниз не копируется: потомок читает флаги предков. Чтение
 * неблокирующее — строку общего родителя блокировать нельзя, читать можно.
 */
final class TaskCancelFlags {

    /** Причина {@code CANCELLED} у сущности, на которой вызвана отмена. */
    static final String SELF = "отменено";

    /** Сущность, своя отметка и отметки предков по ключу сущности одним запросом. */
    private static final String CANCELLED_BY = """
            SELECT e.cancel_requested_at IS NOT NULL AS self,
                   {batch} AS batch_id,
                   {task} AS task_id
              FROM {table} e
             WHERE e.execution_created_at = :executionCreatedAt AND e.id = :id
            """;

    private static final String BATCH_FLAG = """
            (SELECT b.id FROM tc_task_batch b
              WHERE b.execution_created_at = e.execution_created_at AND b.id = e.batch_id
                AND b.cancel_requested_at IS NOT NULL)""";

    private static final String TASK_FLAG = """
            (SELECT t.id FROM tc_task t
              WHERE t.execution_created_at = e.execution_created_at AND t.id = e.task_id
                AND t.cancel_requested_at IS NOT NULL)""";

    private TaskCancelFlags() {}

    /**
     * Кем отменена сущность: ею самой — если на ней отметка, — иначе ближайшим отменённым предком.
     *
     * @param jdbc клиент — в транзакции вызывающего или без неё
     * @param entity сущность
     * @return отменённая сущность — она сама или предок, — или пусто, если отмены нет
     */
    static Optional<TaskEntityRef> cancelledBy(final JdbcClient jdbc, final TaskEntityRef entity) {
        final String sql = CANCELLED_BY
                .replace(TaskRetries.TABLE, entity.level().table())
                .replace("{batch}", entity.level() == TaskLevel.UNIT ? BATCH_FLAG : "NULL::uuid")
                .replace("{task}", entity.level() == TaskLevel.TASK ? "NULL::uuid" : TASK_FLAG);
        return jdbc.sql(sql)
                .param("executionCreatedAt", TaskSql.parameter(entity.executionCreatedAt()))
                .param("id", entity.id())
                .query((rs, rowNum) -> {
                    final UUID batch = rs.getObject("batch_id", UUID.class);
                    final UUID task = rs.getObject("task_id", UUID.class);
                    final TaskEntityRef by;
                    if (rs.getBoolean("self")) {
                        by = entity;
                    } else if (batch != null) {
                        by = new TaskEntityRef(TaskLevel.BATCH, batch, entity.executionCreatedAt());
                    } else if (task != null) {
                        by = new TaskEntityRef(TaskLevel.TASK, task, entity.executionCreatedAt());
                    } else {
                        by = null;
                    }
                    return Optional.ofNullable(by);
                })
                .optional()
                .flatMap(found -> found);
    }

    /**
     * Причина {@code CANCELLED} или {@code SKIPPED} у сущности, отменённой собой или предком.
     *
     * @param entity сущность
     * @param by отменённая сущность
     * @return «отменено» или «отменён предок {@code <уровень> <id>}»
     */
    static String reason(final TaskEntityRef entity, final TaskEntityRef by) {
        return by.equals(entity) ? SELF : ancestor(by);
    }

    /**
     * Причина у потомков отменённой сущности.
     *
     * @param by отменённая сущность
     * @return «отменён предок {@code <уровень> <id>}»
     */
    static String ancestor(final TaskEntityRef by) {
        return "отменён предок " + by.level() + " " + by.id();
    }
}
