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
 * и после того, как драйвер перевёл запрос в подготовленный на сервере. Верхний предел
 * {@code execution_created_at < active_to} так же убирает из плана партиции, заведённые наперёд
 * (docs/adr/0131-claim-upper-partition-bound.md). Берутся вторая и третья партиции наперёд:
 * следующая попадает в диапазон в последний час текущего месяца, и это законно.
 *
 * <p>Контрольный прогон с {@code force_generic_plan} показывает, от чего защищает настройка:
 * общий план блокирует все партиции, хотя строк из старых не читает.
 */
class ClaimPartitionPruningIT {

    /** Больше порога драйвера {@code prepareThreshold = 5}: запрос успевает стать серверным. */
    private static final int EXECUTIONS = 8;

    private TaskTestDatabase db;

    private List<Instant> oldMonths;

    /** Партиции, которые обслуживание заводит наперёд: пустые, выше верхнего предела. */
    private List<Instant> futureMonths;

    @BeforeEach
    void createSchemaWithClosedOldMonths() {
        this.db = TaskTestDatabase.create();
        final Instant previous = RangeCalendar.MONTH.floor(this.db.currentMonth.minus(1, ChronoUnit.DAYS));
        this.oldMonths = List.of(RangeCalendar.MONTH.floor(previous.minus(1, ChronoUnit.DAYS)), previous);
        this.oldMonths.forEach(this.db::createMonth);
        assertThat(this.db.horizon()).isEqualTo(this.db.currentMonth);
        final Instant secondAhead = RangeCalendar.MONTH.next(RangeCalendar.MONTH.next(this.db.currentMonth));
        this.futureMonths = List.of(secondAhead, RangeCalendar.MONTH.next(secondAhead));
        assertThat(this.db
                        .jdbc
                        .sql("SELECT count(*) FROM pg_class WHERE relname = ANY(CAST(:names AS text[]))"
                                + " AND relnamespace = to_regnamespace(current_schema())::oid")
                        .param(
                                "names",
                                this.futureMonths.stream()
                                        .map(lower -> RangeCalendar.partitionName("tc_task", lower))
                                        .toArray(String[]::new))
                        .query(Integer.class)
                        .single())
                .as("партиции наперёд заведены")
                .isEqualTo(this.futureMonths.size());
        this.db.submit(TaskTestDatabase.FLAT_TYPE, 100);
    }

    @Test
    void customPlanNeitherOpensNorLocksPartitionsOutsideActiveRange() {
        for (final TaskClaimer.Ordering ordering : TaskClaimer.Ordering.values()) {
            final InTransaction result = this.runClaimQuery(ordering, "force_custom_plan");

            assertThat(result.lockedOld()).as(ordering.name()).isEmpty();
            assertThat(result.lockedFuture()).as(ordering.name()).isEmpty();
            assertThat(result.plan()).as(ordering.name()).doesNotContain(suffixes(this.oldMonths));
            assertThat(result.plan()).as(ordering.name()).doesNotContain(suffixes(this.futureMonths));
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
                .contains(this.indexOn(partition, "(status, task_type, claim_order_at)"));
        assertThat(this.runClaimQuery(TaskClaimer.Ordering.PRIORITY, "force_custom_plan")
                        .plan())
                .contains(this.indexOn(partition, "(status, task_type, priority, next_attempt_at)"));
        assertThat(this.runClaimQuery(TaskClaimer.Ordering.FIFO, "force_custom_plan")
                        .plan())
                .contains(this.indexOn(partition, "(status, task_type, next_attempt_at)"));
    }

    @Test
    void genericPlanLocksOldPartitionsWhichIsWhatTheSettingPrevents() {
        final InTransaction result = this.runClaimQuery(TaskClaimer.Ordering.DEADLINE, "force_generic_plan");

        assertThat(result.lockedOld()).isNotEmpty();
    }

    private InTransaction runClaimQuery(final TaskClaimer.Ordering ordering, final String planCacheMode) {
        final String query = TaskClaimQuery.branches(ordering, List.of(TaskTestDatabase.FLAT_TYPE));
        return new TransactionTemplate(this.db.transactionManager).execute(status -> {
            final JdbcClient jdbc = this.db.jdbc;
            jdbc.sql("SELECT set_config('plan_cache_mode', :mode, true)")
                    .param("mode", planCacheMode)
                    .query(String.class)
                    .single();
            final TaskPartitionHorizon.ActiveRange range = TaskPartitionHorizon.activeRange(jdbc);
            for (int i = 0; i < EXECUTIONS; i++) {
                bind(jdbc.sql(query), range).query().listOfRows();
            }
            final String plan = String.join(
                    "\n",
                    bind(jdbc.sql("EXPLAIN " + query), range)
                            .query(String.class)
                            .list());
            final List<String> lockedOld = locked(jdbc, suffixes(this.oldMonths));
            final List<String> lockedFuture = locked(jdbc, suffixes(this.futureMonths));
            status.setRollbackOnly();
            return new InTransaction(plan, lockedOld, lockedFuture);
        });
    }

    private static List<String> locked(final JdbcClient jdbc, final List<String> suffixes) {
        return jdbc.sql("""
                        SELECT c.relname FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                         WHERE l.pid = pg_backend_pid() AND c.relname LIKE ANY (CAST(:patterns AS text[]))
                        """)
                .param("patterns", suffixes.stream().map(s -> "%" + s + "%").toArray(String[]::new))
                .query(String.class)
                .list();
    }

    private static JdbcClient.StatementSpec bind(
            final JdbcClient.StatementSpec statement, final TaskPartitionHorizon.ActiveRange range) {
        return TaskClaimQuery.bind(statement, range, List.of(TaskTestDatabase.FLAT_TYPE), 1, 10);
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

    private static List<String> suffixes(final List<Instant> months) {
        return months.stream()
                .map(lower -> RangeCalendar.partitionName("", lower))
                .toList();
    }

    /**
     * Что видно изнутри транзакции захвата.
     *
     * @param plan план запроса
     * @param lockedOld старые партиции и их индексы, на которых транзакция держит блокировки
     * @param lockedFuture заведённые наперёд партиции и их индексы, на которых транзакция держит блокировки
     */
    private record InTransaction(String plan, List<String> lockedOld, List<String> lockedFuture) {}
}
