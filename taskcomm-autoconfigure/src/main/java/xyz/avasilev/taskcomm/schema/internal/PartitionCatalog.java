package xyz.avasilev.taskcomm.schema.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Чтение существующих партиций девяти таблиц из каталога PostgreSQL.
 *
 * <p>Границы берутся из выражения партиции и разбираются в SQL: текст границы выводится в часовом
 * поясе сессии и в нём же приводится обратно к {@code timestamptz}, поэтому момент не зависит
 * от настроек соединения. Партиция {@code DEFAULT} и границы {@code MINVALUE} / {@code MAXVALUE}
 * дают пустые границы — с календарём они не совпадают никогда.
 */
@RequiredArgsConstructor
class PartitionCatalog {

    private static final String PARTITIONS_QUERY = """
            SELECT parent.relname AS parent_name,
                   child.relname  AS partition_name,
                   bounds.value[1]::timestamptz AS lower_bound,
                   bounds.value[2]::timestamptz AS upper_bound
              FROM pg_inherits i
              JOIN pg_class parent ON parent.oid = i.inhparent
              JOIN pg_class child  ON child.oid = i.inhrelid
             CROSS JOIN LATERAL (
                   SELECT regexp_match(pg_get_expr(child.relpartbound, child.oid),
                                       'FROM \\(''([^'']+)''\\) TO \\(''([^'']+)''\\)') AS value) bounds
             WHERE parent.relnamespace = current_schema()::regnamespace
               AND parent.relname IN (:parents)
             ORDER BY lower_bound NULLS FIRST, parent.relname
            """;

    private final JdbcClient jdbc;

    /**
     * Все партиции девяти таблиц в схеме соединения.
     *
     * @return партиции, упорядоченные по нижней границе
     */
    List<ExistingPartition> partitions() {
        return this.jdbc
                .sql(PARTITIONS_QUERY)
                .param("parents", PartitionedTable.tableNames())
                .query((rs, rowNum) -> new ExistingPartition(
                        rs.getString("parent_name"),
                        rs.getString("partition_name"),
                        toInstant(rs.getObject("lower_bound", OffsetDateTime.class)),
                        toInstant(rs.getObject("upper_bound", OffsetDateTime.class))))
                .list();
    }

    private static Instant toInstant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Партиция в каталоге.
     *
     * @param parent имя родительской таблицы
     * @param name имя партиции
     * @param lower нижняя граница, включительно; {@code null} — граница не диапазонная
     * @param upper верхняя граница, не включительно; {@code null} — граница не диапазонная
     */
    record ExistingPartition(String parent, String name, Instant lower, Instant upper) {

        /**
         * Совпадают ли границы с календарём: нижняя выровнена, верхняя — следующая за ней.
         *
         * @param calendar размер партиции из настроек
         * @return {@code true}, если партиция — ровно один диапазон календаря
         */
        boolean matches(final RangeCalendar calendar) {
            return this.lower != null
                    && this.upper != null
                    && calendar.isAligned(this.lower)
                    && calendar.next(this.lower).equals(this.upper);
        }
    }
}
