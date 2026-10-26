package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;

/**
 * Отсечение партиций в запросах подстраховочного поллера (docs/adr/0126-safety-poller.md,
 * docs/adr/0101-active-partition-horizon.md): под {@code force_custom_plan} ни один из них
 * не открывает и не блокирует партиции ниже границы — ни родителей, ни детей, ни попыток
 * в соединениях и подзапросах.
 */
class SafetyPartitionPruningIT {

    /** Больше порога драйвера {@code prepareThreshold = 5}: запрос успевает стать серверным. */
    private static final int EXECUTIONS = 8;

    private TaskTestDatabase db;

    private List<Instant> oldMonths;

    @BeforeEach
    void createSchemaWithClosedOldMonths() {
        this.db = TaskTestDatabase.create();
        final Instant previous = RangeCalendar.MONTH.floor(this.db.currentMonth.minus(1, ChronoUnit.DAYS));
        this.oldMonths = List.of(RangeCalendar.MONTH.floor(previous.minus(1, ChronoUnit.DAYS)), previous);
        this.oldMonths.forEach(this.db::createMonth);
        assertThat(this.db.horizon()).isEqualTo(this.db.currentMonth);
        this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
    }

    @Test
    void safetyQueriesNeitherOpenNorLockPartitionsBelowHorizon() {
        for (final TaskLevel parent : List.of(TaskLevel.TASK, TaskLevel.BATCH)) {
            final List<String> queries = new ArrayList<>();
            TaskChildren.childLevels(parent)
                    .forEach(child -> queries.add(TaskSafetyPoller.closedExpansionsQuery(parent, child)));
            queries.add(TaskSafetyPoller.stuckParentsQuery(parent));
            queries.add(TaskSafetyPoller.cancelledParentsQuery(parent));
            for (final String query : queries) {
                final InTransaction result = this.run(query, parent);

                assertThat(result.lockedOld()).as(query).isEmpty();
                assertThat(result.plan()).as(query).doesNotContain(this.oldSuffixes());
                assertThat(result.plan())
                        .as(query)
                        .contains(RangeCalendar.partitionName(parent.table(), this.db.currentMonth));
            }
        }
    }

    private InTransaction run(final String query, final TaskLevel parent) {
        return new TransactionTemplate(this.db.transactionManager).execute(status -> {
            final JdbcClient jdbc = this.db.jdbc;
            TaskSql.forceCustomPlan(jdbc);
            final Instant activeFrom = TaskPartitionHorizon.activeFrom(jdbc);
            for (int i = 0; i < EXECUTIONS; i++) {
                bind(jdbc.sql(query), activeFrom, parent).query().listOfRows();
            }
            final String plan = String.join(
                    "\n",
                    bind(jdbc.sql("EXPLAIN " + query), activeFrom, parent)
                            .query(String.class)
                            .list());
            final List<String> lockedOld = this.lockedOldPartitions(jdbc);
            status.setRollbackOnly();
            return new InTransaction(plan, lockedOld);
        });
    }

    private List<String> lockedOldPartitions(final JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT c.relname FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                         WHERE l.pid = pg_backend_pid() AND c.relname LIKE ANY (CAST(:patterns AS text[]))
                        """)
                .param(
                        "patterns",
                        this.oldSuffixes().stream().map(s -> "%" + s + "%").toArray(String[]::new))
                .query(String.class)
                .list();
    }

    private static JdbcClient.StatementSpec bind(
            final JdbcClient.StatementSpec statement, final Instant activeFrom, final TaskLevel parent) {
        return statement
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("level", parent.name())
                .param("grace", 60.0)
                .param("limit", TaskSafetyPoller.LIMIT);
    }

    private List<String> oldSuffixes() {
        return this.oldMonths.stream()
                .map(lower -> RangeCalendar.partitionName("", lower))
                .toList();
    }

    /**
     * Что видно изнутри транзакции поиска.
     *
     * @param plan план запроса
     * @param lockedOld старые партиции и их индексы, на которых транзакция держит блокировки
     */
    private record InTransaction(String plan, List<String> lockedOld) {}
}
