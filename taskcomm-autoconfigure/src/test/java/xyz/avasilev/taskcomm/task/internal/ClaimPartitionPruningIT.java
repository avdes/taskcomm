package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;

/**
 * Прототип отсечения партиций по границе до движка (docs/adr/0101-active-partition-horizon.md):
 * запрос захвата с {@code execution_created_at >= active_from} под
 * {@code plan_cache_mode = force_custom_plan} не открывает и не блокирует партиции ниже границы —
 * и после того, как драйвер перевёл запрос в подготовленный на сервере.
 *
 * <p>Контрольный прогон с {@code force_generic_plan} показывает, от чего защищает настройка:
 * общий план блокирует все партиции, хотя строк из старых не читает.
 */
class ClaimPartitionPruningIT {

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
    void customPlanNeitherOpensNorLocksPartitionsBelowHorizon() {
        for (final TaskClaimer.Ordering ordering : TaskClaimer.Ordering.values()) {
            final InTransaction result = this.runClaimQuery(ordering, "force_custom_plan");

            assertThat(result.lockedOld()).as(ordering.name()).isEmpty();
            assertThat(result.plan()).as(ordering.name()).doesNotContain(this.oldSuffixes());
            assertThat(result.plan())
                    .as(ordering.name())
                    .contains(RangeCalendar.partitionName("tc_task", this.db.currentMonth));
        }
    }

    @Test
    void eachClaimFormReadsItsOwnPartialIndex() {
        this.db.jdbc.sql("""
                        INSERT INTO tc_task (id, created_at, updated_at, execution_id, execution_created_at, task_type,
                                             payload, status, priority, next_attempt_at, claim_order_at,
                                             retry_cycle_started_at)
                        SELECT gen_random_uuid(), now(), now(), gen_random_uuid(), now(), :type, '{}',
                               CASE WHEN n % 100 = 0 THEN 'PENDING' ELSE 'SUCCEEDED' END,
                               1 + n % 200, now() - n * interval '1 second', now() + n * interval '1 second', now()
                          FROM generate_series(1, 50000) n
                        """).param("type", TaskTestDatabase.FLAT_TYPE).update();
        this.db.jdbc.sql("ANALYZE tc_task").update();
        final String partition = RangeCalendar.partitionName("tc_task", this.db.currentMonth);

        assertThat(this.runClaimQuery(TaskClaimer.Ordering.DEADLINE, "force_custom_plan")
                        .plan())
                .contains(this.indexOn(partition, "(status, claim_order_at)"));
        assertThat(this.runClaimQuery(TaskClaimer.Ordering.PRIORITY, "force_custom_plan")
                        .plan())
                .contains(this.indexOn(partition, "(status, priority, next_attempt_at)"));
        assertThat(this.runClaimQuery(TaskClaimer.Ordering.FIFO, "force_custom_plan")
                        .plan())
                .contains(this.indexOn(partition, "(status, next_attempt_at)"));
    }

    @Test
    void genericPlanLocksOldPartitionsWhichIsWhatTheSettingPrevents() {
        final InTransaction result = this.runClaimQuery(TaskClaimer.Ordering.DEADLINE, "force_generic_plan");

        assertThat(result.lockedOld()).isNotEmpty();
    }

    private InTransaction runClaimQuery(final TaskClaimer.Ordering ordering, final String planCacheMode) {
        final String query = TaskClaimer.branchesQuery(ordering);
        return new TransactionTemplate(this.db.transactionManager).execute(status -> {
            final JdbcClient jdbc = this.db.jdbc;
            jdbc.sql("SELECT set_config('plan_cache_mode', :mode, true)")
                    .param("mode", planCacheMode)
                    .query(String.class)
                    .single();
            final Instant activeFrom = TaskPartitionHorizon.activeFrom(jdbc);
            for (int i = 0; i < EXECUTIONS; i++) {
                bind(jdbc.sql(query), activeFrom).query().listOfRows();
            }
            final String plan = String.join(
                    "\n",
                    bind(jdbc.sql("EXPLAIN " + query), activeFrom)
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

    private static JdbcClient.StatementSpec bind(final JdbcClient.StatementSpec statement, final Instant activeFrom) {
        return statement
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("types", new String[] {TaskTestDatabase.FLAT_TYPE})
                .param("limit", 1)
                .param("expiredLimit", 10);
    }

    private String indexOn(final String partition, final String columns) {
        return this.db
                .jdbc
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = :partition"
                        + " AND indexdef LIKE :columns")
                .param("partition", partition)
                .param("columns", "%" + columns + " WHERE%")
                .query(String.class)
                .single();
    }

    private List<String> oldSuffixes() {
        return this.oldMonths.stream()
                .map(lower -> RangeCalendar.partitionName("", lower))
                .toList();
    }

    /**
     * Что видно изнутри транзакции захвата.
     *
     * @param plan план запроса
     * @param lockedOld старые партиции и их индексы, на которых транзакция держит блокировки
     */
    private record InTransaction(String plan, List<String> lockedOld) {}
}
