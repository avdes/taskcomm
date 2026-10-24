package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Подготовка подсистемы задач при старте, после проверки схемы.
 *
 * <ul>
 *   <li><b>Синхронизация белого списка полей поиска</b> в {@code tc_searchable_field}
 *       (docs/adr/0037-search-field-registry.md, docs/adr/0127-search-field-sync.md): строка
 *       находится по сигнатуре; нашли — {@code last_declared_at} двигается всегда, оформление
 *       приводится к объявленному, а {@code updated_at} — только если оформление изменилось;
 *       не нашли — вставляется. Не объявленные больше строки не удаляются никогда, кроме как
 *       по {@code taskcomm.search.remove-undeclared-fields}, и каждая удалённая — отдельный WARN.
 *   <li><b>Громкое предупреждение об осиротевших задачах</b> — незавершённых задачах типов без
 *       процессора, с числом по типу (docs/adr/0026-contract-shape-marking.md). Считаются
 *       в активных партициях (docs/adr/0101-active-partition-horizon.md).
 * </ul>
 */
@Slf4j
public class TaskStartup implements InitializingBean {

    private static final String UPSERT_FIELD = """
            INSERT INTO tc_searchable_field AS f (
                id, created_at, updated_at, level, task_type, field_name, field_type, json_path,
                field_title, field_description, last_declared_at)
            VALUES (:id, now(), now(), :level, :taskType, :name, :type, :path, :title, :description, now())
            ON CONFLICT ON CONSTRAINT tc_searchable_field_signature_uq DO UPDATE
               SET last_declared_at = now(),
                   field_title = EXCLUDED.field_title,
                   field_description = EXCLUDED.field_description,
                   updated_at = CASE WHEN (f.field_title, f.field_description)
                                          IS DISTINCT FROM (EXCLUDED.field_title, EXCLUDED.field_description)
                                     THEN now() ELSE f.updated_at END
            """;

    private static final String ALL_FIELDS = """
            SELECT id, level, task_type, field_name, field_type, json_path FROM tc_searchable_field
            """;

    private static final String DELETE_FIELD = "DELETE FROM tc_searchable_field WHERE id = :id";

    private static final String ORPHANS = """
            SELECT task_type, count(*) AS unfinished FROM (
                SELECT task_type FROM tc_task
                 WHERE execution_created_at >= :activeFrom AND status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN')
                   AND task_type <> ALL(CAST(:types AS text[]))
                UNION ALL
                SELECT task_type FROM tc_task_batch
                 WHERE execution_created_at >= :activeFrom
                   AND status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING', 'AWAITING_CHILDREN')
                   AND task_type <> ALL(CAST(:types AS text[]))
                UNION ALL
                SELECT task_type FROM tc_task_unit
                 WHERE execution_created_at >= :activeFrom AND status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING')
                   AND task_type <> ALL(CAST(:types AS text[]))
            ) o
             GROUP BY task_type
             ORDER BY task_type
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final UuidV7 ids;

    private final TaskProcessorRegistry registry;

    private final boolean removeUndeclared;

    /**
     * Собирает подготовку.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param registry реестр процессоров с объявлениями полей поиска
     * @param removeUndeclared удалять строки полей, которых не объявляет ни один процессор
     */
    public TaskStartup(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final TaskProcessorRegistry registry,
            final boolean removeUndeclared) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = TaskSql.transactions(transactionManager);
        this.ids = ids;
        this.registry = registry;
        this.removeUndeclared = removeUndeclared;
    }

    @Override
    public void afterPropertiesSet() {
        this.syncSearchFields();
        this.warnAboutOrphans();
    }

    /** Приводит {@code tc_searchable_field} к объявлениям процессоров одной транзакцией. */
    void syncSearchFields() {
        final List<SearchFieldDeclaration> declared = this.registry.searchFields();
        this.transactions.executeWithoutResult(status -> {
            declared.forEach(this::upsert);
            if (this.removeUndeclared) {
                this.removeUndeclared(declared);
            }
        });
    }

    private void upsert(final SearchFieldDeclaration declaration) {
        this.jdbc
                .sql(UPSERT_FIELD)
                .param("id", this.ids.next())
                .param("level", declaration.level().name())
                .param("taskType", declaration.taskType())
                .param("name", declaration.field().name())
                .param("type", declaration.field().type().name())
                .param("path", declaration.field().jsonPath())
                .param("title", declaration.field().title())
                .param("description", declaration.field().description())
                .update();
    }

    private void removeUndeclared(final List<SearchFieldDeclaration> declared) {
        final Set<String> signatures = declared.stream()
                .map(d -> signature(
                        d.level().name(),
                        d.taskType(),
                        d.field().name(),
                        d.field().type().name(),
                        d.field().jsonPath()))
                .collect(Collectors.toSet());
        final Map<UUID, String> stored = new LinkedHashMap<>();
        this.jdbc
                .sql(ALL_FIELDS)
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        signature(
                                rs.getString("level"),
                                rs.getString("task_type"),
                                rs.getString("field_name"),
                                rs.getString("field_type"),
                                rs.getString("json_path"))))
                .list()
                .forEach(entry -> stored.put(entry.getKey(), entry.getValue()));
        stored.forEach((id, signature) -> {
            if (!signatures.contains(signature)) {
                this.jdbc.sql(DELETE_FIELD).param("id", id).update();
                log.warn(
                        "Поле поиска {} удалено по taskcomm.search.remove-undeclared-fields: его не объявляет ни один "
                                + "процессор. Поиск по нему перестаёт находить старые нагрузки; вернуть — снова "
                                + "объявить поле и перезапуститься.",
                        signature);
            }
        });
    }

    /** Предупреждает об осиротевших задачах; ошибку чтения не роняет в старт — это только предупреждение. */
    void warnAboutOrphans() {
        try {
            final Instant activeFrom = TaskPartitionHorizon.activeFrom(this.jdbc);
            final Map<String, Long> orphans = new LinkedHashMap<>();
            this.jdbc
                    .sql(ORPHANS)
                    .param("activeFrom", TaskSql.parameter(activeFrom))
                    .param("types", this.registry.types().toArray(String[]::new))
                    .query((rs, rowNum) -> Map.entry(rs.getString("task_type"), rs.getLong("unfinished")))
                    .list()
                    .forEach(entry -> orphans.put(entry.getKey(), entry.getValue()));
            if (!orphans.isEmpty()) {
                log.warn(
                        "Осиротевшие задачи — незавершённые задачи типов без процессора: {}. Их никто не захватывает, "
                                + "состояние они не меняют и ждут, пока процессор вернут. Процессор типа задачи не "
                                + "удаляется никогда (docs/adr/0029-processor-never-deleted.md).",
                        orphans);
            }
        } catch (final RuntimeException failure) {
            log.warn("Не удалось сосчитать осиротевшие задачи при старте", failure);
        }
    }

    private static String signature(
            final String level, final String taskType, final String name, final String type, final String path) {
        return level + " '" + taskType + "' " + name + " " + type + " " + path;
    }
}
