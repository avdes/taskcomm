package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Удаление результатов детей после успешной сборки (docs/adr/0058-task-result-storage.md,
 * docs/adr/0122-child-results-discard-sweep.md).
 *
 * <p>Отметка {@code child_results_discarded_at} ставится на родителе <b>до</b> удаления, своей
 * транзакцией: на ней держится отказ перезапуска подзадачи, и условная отметка решает, какой
 * экземпляр удаляет. Удаляются результаты детей <b>всех</b> попыток раскрытия родителя, пачками
 * по ключу детей, каждая своей транзакцией. Удаление прервалось — хвост лежит до дропа партиции.
 *
 * <p>Родителей находит подметальщик по частичному индексу «собран, результаты детей
 * не удалялись». Режим {@code immediately} удаляет сразу после сборки тем же кодом, а подметальщик
 * с нулевым сроком добирает тех, до кого воркер не дошёл.
 */
@Slf4j
class TaskResultDiscard {

    /** Такт подметальщика. */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    /** Сколько родителей одной таблицы берёт один такт. */
    static final int SWEEP_LIMIT = 100;

    /** Условие совпадает с условием частичных индексов {@code *_results_discard_idx}. */
    private static final String DUE = """
            SELECT execution_created_at, id FROM {table}
             WHERE child_results_discarded_at IS NULL
               AND phase = 'COLLECT'
               AND status IN ('SUCCEEDED', 'WARNING', 'SKIPPED')
               AND updated_at <= now() - make_interval(secs => :delay)
             ORDER BY updated_at
             LIMIT :limit
            """;

    private static final String MARK = """
            UPDATE {table}
               SET child_results_discarded_at = now(), updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id AND child_results_discarded_at IS NULL
            """;

    private static final String EXPANSIONS = """
            SELECT id FROM tc_task_attempt
             WHERE execution_created_at = :executionCreatedAt AND level = :level AND target_id = :id AND phase = 'EXPAND'
            """;

    /** Пачка детей по ключу и удаление их результатов; возвращает ключ следующей пачки. */
    private static final String DELETE_PAGE = """
            WITH page AS (SELECT c.id FROM {table} c
                           WHERE c.execution_created_at = :executionCreatedAt
                             AND c.parent_attempt_id = :attemptId
                             AND c.id > :after
                           ORDER BY c.id
                           LIMIT :chunk),
                 gone AS (DELETE FROM tc_task_result r
                           USING page
                           WHERE r.execution_created_at = :executionCreatedAt
                             AND r.level = :level
                             AND r.target_id = page.id)
            SELECT id FROM page ORDER BY id DESC LIMIT 1
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final ResultDiscard settings;

    TaskResultDiscard(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final ResultDiscard settings) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.settings = settings;
    }

    /**
     * Нужен ли подметальщик.
     *
     * @return {@code false} в режиме {@code never}
     */
    boolean enabled() {
        return this.settings.mode() != ResultDiscard.Mode.NEVER;
    }

    /**
     * Удаляет результаты детей сразу после успешной сборки, если так настроено.
     *
     * @param parent собранный родитель
     */
    void afterCollect(final TaskEntityRef parent) {
        if (this.settings.mode() == ResultDiscard.Mode.IMMEDIATELY) {
            this.discard(parent);
        }
    }

    /**
     * Один такт подметальщика: родители, собранные не позже срока назад и ещё не очищенные.
     *
     * @return сколько родителей очищено этим тактом
     */
    int sweep() {
        int discarded = 0;
        for (final TaskLevel level : List.of(TaskLevel.BATCH, TaskLevel.TASK)) {
            final List<TaskEntityRef> due = this.jdbc
                    .sql(DUE.replace(TaskRetries.TABLE, level.table()))
                    .param("delay", TaskSql.seconds(this.settings.delay()))
                    .param("limit", SWEEP_LIMIT)
                    .query((rs, rowNum) -> new TaskEntityRef(
                            level,
                            rs.getObject("id", UUID.class),
                            rs.getObject("execution_created_at", OffsetDateTime.class)
                                    .toInstant()))
                    .list();
            for (final TaskEntityRef parent : due) {
                if (this.discard(parent)) {
                    discarded++;
                }
            }
        }
        return discarded;
    }

    /**
     * Ставит отметку и удаляет результаты детей родителя.
     *
     * @param parent родитель
     * @return {@code false}, если отметка уже стояла — удаляет тот, кто её поставил
     */
    boolean discard(final TaskEntityRef parent) {
        final Integer marked = this.transactions.execute(status -> this.jdbc
                .sql(MARK.replace(TaskRetries.TABLE, parent.level().table()))
                .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                .param("id", parent.id())
                .update());
        if (marked == null || marked == 0) {
            return false;
        }
        final List<UUID> expansions = this.jdbc
                .sql(EXPANSIONS)
                .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                .param("level", parent.level().name())
                .param("id", parent.id())
                .query(UUID.class)
                .list();
        for (final UUID attemptId : expansions) {
            for (final TaskLevel level : TaskChildren.childLevels(parent.level())) {
                this.deleteResults(parent, attemptId, level);
            }
        }
        log.debug("Результаты детей {} {} удалены", parent.level(), parent.id());
        return true;
    }

    private void deleteResults(final TaskEntityRef parent, final UUID attemptId, final TaskLevel level) {
        UUID after = new UUID(0, 0);
        while (after != null) {
            final UUID from = after;
            after = this.transactions.execute(status -> this.jdbc
                    .sql(DELETE_PAGE.replace(TaskRetries.TABLE, level.table()))
                    .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                    .param("attemptId", attemptId)
                    .param("after", from)
                    .param("chunk", TaskChildren.CHUNK)
                    .param("level", level.name())
                    .query(UUID.class)
                    .optional()
                    .orElse(null));
        }
    }
}
