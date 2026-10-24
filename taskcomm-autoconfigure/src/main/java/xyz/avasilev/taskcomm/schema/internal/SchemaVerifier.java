package xyz.avasilev.taskcomm.schema.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Проверка схемы при старте (docs/rules/schema.md): все таблицы стартера на месте, у каждой —
 * все колонки этой версии, партиционированные таблицы действительно партиционированы. Не совпало —
 * приложение не стартует, а сообщение говорит, какие миграции применить, вместо ошибки
 * «column does not exist» на третий день работы.
 *
 * <p>Лишние колонки и таблицы ошибкой не считаются: при rolling update схема бывает новее кода.
 */
@RequiredArgsConstructor
public class SchemaVerifier {

    /** Версия схемы, которую ожидает этот код. */
    static final String SCHEMA_VERSION = "2.0.0";

    /** Таблицы и колонки версии {@value #SCHEMA_VERSION}, в порядке файла схемы. */
    static final Map<String, List<String>> EXPECTED_COLUMNS = expectedColumns();

    private static final String CATALOG_QUERY = """
            SELECT c.relname AS table_name, c.relkind AS kind, a.attname AS column_name
              FROM pg_class c
              LEFT JOIN pg_attribute a
                ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
             WHERE c.relnamespace = current_schema()::regnamespace
               AND c.relkind IN ('r', 'p')
               AND c.relname IN (:tables)
            """;

    private final JdbcClient jdbc;

    /**
     * Сверяет схему соединения с ожидаемой.
     *
     * @throws TaskcommConfigurationException если таблицы или колонки не хватает или таблица
     *     не партиционирована
     */
    public void verify() {
        final Map<String, Set<String>> actualColumns = new HashMap<>();
        final Set<String> partitioned = new LinkedHashSet<>();
        this.jdbc
                .sql(CATALOG_QUERY)
                .param("tables", EXPECTED_COLUMNS.keySet())
                .query((rs, rowNum) -> {
                    final String table = rs.getString("table_name");
                    final Set<String> columns = actualColumns.computeIfAbsent(table, key -> new LinkedHashSet<>());
                    final String column = rs.getString("column_name");
                    if (column != null) {
                        columns.add(column);
                    }
                    if ("p".equals(rs.getString("kind"))) {
                        partitioned.add(table);
                    }
                    return table;
                })
                .list();

        final List<String> problems = this.problems(actualColumns, partitioned);
        if (!problems.isEmpty()) {
            final String schema = this.jdbc
                    .sql("SELECT current_schema()")
                    .query(String.class)
                    .optional()
                    .orElse("не задана");
            throw new TaskcommConfigurationException("Схема БД '" + schema + "' не соответствует taskcomm "
                    + SCHEMA_VERSION + ": " + String.join("; ", problems) + ". Примените миграции taskcomm "
                    + SCHEMA_VERSION + ": на Liquibase — db/changelog/taskcomm/master.xml, другим инструментом — "
                    + "файлы db/changelog/taskcomm/*.sql из jar стартера. Таблицы ищутся в схеме соединения "
                    + "(current_schema).");
        }
    }

    private List<String> problems(final Map<String, Set<String>> actualColumns, final Set<String> partitioned) {
        final List<String> missingTables = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        for (final Map.Entry<String, List<String>> expected : EXPECTED_COLUMNS.entrySet()) {
            final String table = expected.getKey();
            final Set<String> actual = actualColumns.get(table);
            if (actual == null) {
                missingTables.add(table);
                continue;
            }
            final List<String> missingColumns = expected.getValue().stream()
                    .filter(column -> !actual.contains(column))
                    .toList();
            if (!missingColumns.isEmpty()) {
                problems.add("в таблице " + table + " нет колонок " + String.join(", ", missingColumns));
            }
            if (PartitionedTable.tableNames().contains(table) && !partitioned.contains(table)) {
                problems.add("таблица " + table + " не партиционирована");
            }
        }
        if (!missingTables.isEmpty()) {
            problems.addFirst("нет таблиц " + String.join(", ", missingTables));
        }
        return problems;
    }

    private static Map<String, List<String>> expectedColumns() {
        final Map<String, List<String>> tables = new LinkedHashMap<>();
        tables.put("tc_execution", columns("id created_at updated_at"));
        tables.put(
                "tc_task",
                columns("id created_at updated_at execution_id execution_created_at task_type payload"
                        + " status status_description contract_shape phase priority next_attempt_at claim_order_at"
                        + " retry_cycle_started_at cancel_requested_at child_results_discarded_at"
                        + " origin_type origin_id origin_attempt_id origin_relation"));
        tables.put(
                "tc_task_batch",
                columns("id created_at updated_at execution_id execution_created_at task_id"
                        + " parent_attempt_id task_type payload status status_description phase priority next_attempt_at"
                        + " claim_order_at retry_cycle_started_at cancel_requested_at child_results_discarded_at"));
        tables.put(
                "tc_task_unit",
                columns("id created_at updated_at execution_id execution_created_at task_id"
                        + " batch_id parent_attempt_id task_type payload status status_description priority next_attempt_at"
                        + " claim_order_at retry_cycle_started_at cancel_requested_at"));
        tables.put(
                "tc_communication",
                columns("id created_at updated_at execution_id execution_created_at"
                        + " direction source destination request_payload contract_shape phase status status_description"
                        + " priority next_attempt_at claim_order_at retry_cycle_started_at cancel_requested_at"
                        + " origin_type origin_id origin_attempt_id origin_relation"));
        tables.put(
                "tc_task_attempt",
                columns("id created_at updated_at execution_created_at level target_id phase"
                        + " locked_by locked_until lease_token lease_renewals started_at finished_at status"
                        + " status_description"));
        tables.put(
                "tc_communication_attempt",
                columns("id created_at updated_at execution_created_at"
                        + " communication_id phase locked_by locked_until lease_token lease_renewals started_at finished_at"
                        + " status status_description request_id request_at request_headers request_body request_payload"
                        + " response_id response_at response_status response_headers response_body response_payload"));
        tables.put(
                "tc_task_result",
                columns("id created_at updated_at execution_created_at attempt_id level" + " target_id result"));
        tables.put(
                "tc_communication_dedup",
                columns("id created_at updated_at execution_created_at"
                        + " communication_id direction external_system subject dedup_key finished_at succeeded_at"));
        tables.put(
                "tc_communication_correlation",
                columns("id created_at updated_at external_system"
                        + " correlation_id communication_id execution_id execution_created_at"));
        tables.put(
                "tc_searchable_field",
                columns("id created_at updated_at level task_type field_name field_type"
                        + " json_path field_title field_description last_declared_at"));
        tables.put("tc_partition_horizon", columns("id created_at updated_at active_from"));
        return Collections.unmodifiableMap(tables);
    }

    private static List<String> columns(final String names) {
        return List.of(names.split(" "));
    }
}
