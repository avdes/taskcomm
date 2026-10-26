package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;

/**
 * Прототип блокировок захвата по типам (docs/adr/0129-claim-by-type-share-locking.md) — на запросе
 * такта {@link TaskClaimQuery#branches}: индексы с типом на втором месте, каждый тип поллера
 * спрашивается своей пробой и блокирует только свою долю слитой головы.
 *
 * <ul>
 *   <li>строки чужих и осиротевших типов не читаются — ни в одной из трёх форм захвата;
 *   <li>блокируется не больше строк, чем захватывает такт, а не типы × лимит;
 *   <li>второй экземпляр, пока первый держит свои блокировки, получает следующие строки, а не пусто;
 *   <li>под {@code force_custom_plan} партиции ниже границы не открываются и не блокируются.
 * </ul>
 *
 * <p>Контрольный прогон отвергнутой формы — свой лимит у каждого типа — блокирует типы × лимит.
 */
class ClaimByTypeLockingIT {

    /** Больше порога драйвера {@code prepareThreshold = 5}: запрос успевает стать серверным. */
    private static final int EXECUTIONS = 8;

    /** Готовые строки типа: самые старые — первыми, с шагом в миллисекунду; приоритет по кругу. */
    private static final String READY = """
            INSERT INTO tc_task (
                id, created_at, updated_at, execution_id, execution_created_at, task_type, payload, status,
                priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
            SELECT gen_random_uuid(), now(), now(), gen_random_uuid(), :executionCreatedAt, :type, '{}'::jsonb,
                   'PENDING', 1 + g % 200, r.at, r.at + make_interval(secs => (1 + g % 200) * 60), now()
              FROM generate_series(1, :count) g
              CROSS JOIN LATERAL (SELECT now() - make_interval(secs => :ageSeconds) + make_interval(secs => g * 0.001)
                                      AS at) r
            """;

    private static final Pattern REMOVED = Pattern.compile("Rows Removed by Filter: (\\d+)");

    private static final Pattern TASK_SCAN = Pattern.compile(" on tc_task_p\\d+ .*actual rows=(\\d+) loops=(\\d+)");

    private TaskTestDatabase db;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
    }

    @Test
    void eachClaimFormReadsOnlyPolledTypesByItsTypeIndex() {
        this.ready("orphan", 20_000, 7200);
        this.ready("lagging", 20_000, 7200);
        this.ready("live", 300, 3600);
        this.ready("other", 300, 3500);
        this.db.jdbc.sql("ANALYZE tc_task").update();
        final List<String> types = List.of("live", "other");
        final int limit = 50;
        final String partition = RangeCalendar.partitionName("tc_task", this.db.currentMonth);

        for (final TaskClaimer.Ordering ordering : TaskClaimer.Ordering.values()) {
            final String plan = this.transaction(jdbc -> TaskClaimQuery.bind(
                            jdbc.sql("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF) "
                                    + TaskClaimQuery.branches(ordering, types)),
                            TaskPartitionHorizon.activeRange(jdbc),
                            types,
                            limit,
                            limit)
                    .query(String.class)
                    .list()
                    .stream()
                    .collect(Collectors.joining("\n")));

            assertThat(plan).as(ordering.name()).contains(this.indexOn(partition, typeIndex(ordering)));
            assertThat(touchedRows(plan))
                    .as(ordering.name() + ": прочитано строк задач")
                    .isBetween((long) limit, 2L * types.size() * limit);
        }
    }

    @Test
    void shareLocksOnlyWhatTheTickClaims() {
        final List<String> types =
                IntStream.rangeClosed(1, 20).mapToObj(n -> "t" + n).toList();
        for (int n = 0; n < types.size(); n++) {
            this.ready(types.get(n), 200, 3600 + n * 7);
        }
        this.db.jdbc.sql("ANALYZE tc_task").update();
        final int limit = 50;

        final Tick tick = this.locking(TaskClaimQuery.branches(TaskClaimer.Ordering.DEADLINE, types), types, limit);

        assertThat(tick.ids()).hasSize(limit);
        assertThat(tick.locked()).isEqualTo(limit);
        assertThat(tick.ids()).containsExactlyInAnyOrderElementsOf(this.globalHead(types, limit));
    }

    @Test
    void ownLimitPerTypeLocksTypesTimesLimitWhichIsWhatTheShareAvoids() {
        final List<String> types =
                IntStream.rangeClosed(1, 20).mapToObj(n -> "t" + n).toList();
        types.forEach(type -> this.ready(type, 200, 3600));
        this.db.jdbc.sql("ANALYZE tc_task").update();
        final int limit = 50;

        final Tick tick = this.locking(ownLimitQuery(types), types, limit);

        assertThat(tick.ids()).hasSize(limit);
        assertThat(tick.locked()).isEqualTo(types.size() * limit);
    }

    @Test
    void secondInstanceTakesNextRowsWhileFirstHoldsItsLocks() throws SQLException {
        this.ready("live", 300, 3600);
        this.ready("other", 300, 3500);
        this.db.jdbc.sql("ANALYZE tc_task").update();
        final int limit = 50;

        for (final List<String> types : List.of(List.of("live"), List.of("live", "other"))) {
            try (Connection first = this.db.dataSource.getConnection();
                    Connection second = this.db.dataSource.getConnection()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                final List<UUID> byFirst = claim(first, types, limit);
                final List<UUID> bySecond = claim(second, types, limit);
                first.rollback();
                second.rollback();

                assertThat(byFirst).as(types.toString()).hasSize(limit);
                assertThat(bySecond).as(types.toString()).hasSize(limit).doesNotContainAnyElementsOf(byFirst);
            }
        }
    }

    @Test
    void customPlanNeitherOpensNorLocksPartitionsBelowHorizon() {
        final Instant previous = RangeCalendar.MONTH.floor(this.db.currentMonth.minus(1, ChronoUnit.DAYS));
        final List<Instant> oldMonths =
                List.of(RangeCalendar.MONTH.floor(previous.minus(1, ChronoUnit.DAYS)), previous);
        oldMonths.forEach(this.db::createMonth);
        assertThat(this.db.horizon()).isEqualTo(this.db.currentMonth);
        this.ready("live", 100, 3600);
        final List<String> oldSuffixes = oldMonths.stream()
                .map(lower -> RangeCalendar.partitionName("", lower))
                .toList();
        final List<String> types = List.of("live", "other");

        for (final TaskClaimer.Ordering ordering : TaskClaimer.Ordering.values()) {
            final String query = TaskClaimQuery.branches(ordering, types);
            final InTransaction result = this.transaction(jdbc -> {
                final TaskPartitionHorizon.ActiveRange range = TaskPartitionHorizon.activeRange(jdbc);
                for (int i = 0; i < EXECUTIONS; i++) {
                    TaskClaimQuery.bind(jdbc.sql(query), range, types, 1, 10)
                            .query()
                            .listOfRows();
                }
                return new InTransaction(
                        String.join(
                                "\n",
                                TaskClaimQuery.bind(jdbc.sql("EXPLAIN " + query), range, types, 1, 10)
                                        .query(String.class)
                                        .list()),
                        jdbc.sql("""
                                        SELECT c.relname FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                                         WHERE l.pid = pg_backend_pid()
                                           AND c.relname LIKE ANY (CAST(:patterns AS text[]))
                                        """)
                                .param(
                                        "patterns",
                                        oldSuffixes.stream()
                                                .map(s -> "%" + s + "%")
                                                .toArray(String[]::new))
                                .query(String.class)
                                .list());
            });

            assertThat(result.lockedOld()).as(ordering.name()).isEmpty();
            assertThat(result.plan()).as(ordering.name()).doesNotContain(oldSuffixes);
        }
    }

    /**
     * Отвергнутая форма: у каждого типа свой лимит под блокировкой. Только для контрольного прогона;
     * параметры — как у запроса такта.
     */
    private static String ownLimitQuery(final List<String> types) {
        return IntStream.range(0, types.size())
                        .mapToObj(n -> "type" + n + " AS (SELECT e.id AS target_id, e.claim_order_at FROM tc_task e"
                                + " WHERE e.status = 'PENDING' AND e.task_type = :type" + n
                                + " AND e.next_attempt_at <= now() AND e.execution_created_at >= :activeFrom"
                                + " ORDER BY e.claim_order_at LIMIT :limit FOR UPDATE OF e SKIP LOCKED)")
                        .collect(Collectors.joining(",\n", "WITH ", "\n"))
                + "SELECT k.target_id FROM ("
                + IntStream.range(0, types.size())
                        .mapToObj(n -> "SELECT * FROM type" + n)
                        .collect(Collectors.joining(" UNION ALL "))
                + ") k ORDER BY k.claim_order_at LIMIT :limit";
    }

    private static String typeIndex(final TaskClaimer.Ordering ordering) {
        return switch (ordering) {
            case DEADLINE -> "(status, task_type, claim_order_at)";
            case PRIORITY -> "(status, task_type, priority, next_attempt_at)";
            case FIFO -> "(status, task_type, next_attempt_at)";
        };
    }

    /** Сколько строк задач прочитали сканы: отданные наверх и отброшенные фильтром. */
    private static long touchedRows(final String plan) {
        long touched = 0;
        final Matcher removed = REMOVED.matcher(plan);
        while (removed.find()) {
            touched += Long.parseLong(removed.group(1));
        }
        final Matcher scans = TASK_SCAN.matcher(plan);
        while (scans.find()) {
            touched += Long.parseLong(scans.group(1)) * Long.parseLong(scans.group(2));
        }
        return touched;
    }

    /** Выполняет запрос, возвращающий {@code target_id}, и считает строки задач под блокировкой транзакции. */
    private Tick locking(final String query, final List<String> types, final int limit) {
        return this.transaction(jdbc -> {
            final List<UUID> ids = TaskClaimQuery.bind(
                            jdbc.sql(query), TaskPartitionHorizon.activeRange(jdbc), types, limit, limit)
                    .query((rs, rowNum) -> rs.getObject("target_id", UUID.class))
                    .list();
            final int locked = jdbc.sql("SELECT count(*) FROM tc_task WHERE xmax = pg_current_xact_id()::xid")
                    .query(Integer.class)
                    .single();
            return new Tick(ids, locked);
        });
    }

    /** Такт захвата на своём соединении — транзакция остаётся открытой. */
    private static List<UUID> claim(final Connection connection, final List<String> types, final int limit) {
        final JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        TaskSql.forceCustomPlan(jdbc);
        return TaskClaimQuery.bind(
                        jdbc.sql(TaskClaimQuery.branches(TaskClaimer.Ordering.DEADLINE, types)),
                        TaskPartitionHorizon.activeRange(jdbc),
                        types,
                        limit,
                        limit)
                .query((rs, rowNum) -> rs.getObject("target_id", UUID.class))
                .list();
    }

    /** Своя транзакция с {@code force_custom_plan}, откатывается в конце. */
    private <T> T transaction(final Function<JdbcClient, T> body) {
        try (Connection connection = this.db.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            final JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            TaskSql.forceCustomPlan(jdbc);
            final T result = body.apply(jdbc);
            connection.rollback();
            return result;
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private List<UUID> globalHead(final List<String> types, final int limit) {
        return this.db
                .jdbc
                .sql("SELECT id FROM tc_task WHERE task_type = ANY(CAST(:types AS text[])) AND status = 'PENDING'"
                        + " ORDER BY claim_order_at LIMIT :limit")
                .param("types", types.toArray(String[]::new))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private void ready(final String type, final int count, final int ageSeconds) {
        this.db
                .jdbc
                .sql(READY)
                .param("executionCreatedAt", TaskSql.parameter(this.db.now()))
                .param("type", type)
                .param("count", count)
                .param("ageSeconds", ageSeconds)
                .update();
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

    /**
     * Что видно изнутри транзакции захвата.
     *
     * @param ids захваченные строки
     * @param locked сколько строк задач заблокировала транзакция
     */
    private record Tick(List<UUID> ids, int locked) {}

    /**
     * План и блокировки старых партиций из транзакции захвата.
     *
     * @param plan план запроса
     * @param lockedOld старые партиции и их индексы, на которых транзакция держит блокировки
     */
    private record InTransaction(String plan, List<String> lockedOld) {}
}
