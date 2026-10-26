package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Нагрузочный сценарий захвата задач (docs/adr/0081-claim-scan-filters-load-scenario.md,
 * docs/adr/0128-claim-load-scenario.md). Запускается задачей {@code loadTest}, вне {@code check}.
 *
 * <p>Два прогона в одном контейнере на свежих схемах: базовый — только очередь живого типа; с помехами —
 * та же очередь плюс миллион осиротевших подзадач, очередь отставшего выделенного поллера, отложенные
 * строки по окну {@code priority × K} и партиции за три года. Критерий, зафиксированный до первого
 * прогона: с помехами не хуже 2× базового по медиане и p95 такта и по времени опустошения очереди.
 * Цена {@code force_custom_plan} только замеряется.
 *
 * <p>Захваченное после каждого такта закрывается настоящим закрытием попытки, как это делают воркеры
 * (docs/adr/0130-load-scenario-closes-claimed-work.md): открытых попыток не больше такта. Закрытие
 * идёт вне замера такта и вычитается из времени опустошения.
 *
 * <p>База — на пуле соединений, как у потребителя (docs/adr/0132-load-scenario-on-pool.md): иначе
 * каждый такт платит за холодный каталог нового соединения.
 */
class ClaimLoadScenario {

    private static final String LIVE = "live";

    private static final int LIVE_TASKS = 20_000;

    private static final int ORPHAN_UNITS = 1_000_000;

    private static final int LAGGING_TASKS = 200_000;

    private static final int DEFERRED_TASKS = 200_000;

    private static final int MONTHS_BACK = 36;

    private static final int TICK = 100;

    private static final double MAX_DEGRADATION = 2.0;

    private static final int PLAN_EXECUTIONS = 100;

    private static final Map<String, ContractShape> TYPES = Map.of(LIVE, ContractShape.FLAT);

    private static final PollerSettings MAIN = TaskTestSettings.mainPoller(ClaimForm.DEADLINE);

    private static final AttemptSettings SETTINGS = TaskTestSettings.settings(3, Duration.ZERO, Duration.ofMinutes(30));

    private static final String TASKS = """
            INSERT INTO tc_task (
                id, created_at, updated_at, execution_id, execution_created_at, task_type, payload, status,
                priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
            SELECT gen_random_uuid(), now(), now(), gen_random_uuid(), :executionCreatedAt, :type, '{}'::jsonb,
                   'PENDING', 100, r.at, r.at + make_interval(secs => 100 * 60), now()
              FROM generate_series(1, :count) g
              CROSS JOIN LATERAL (SELECT now() - make_interval(secs => :ageSeconds) + make_interval(secs => g * 0.001)
                                      AS at) r
            """;

    /**
     * Отложенные строки: готовность через час-три, приоритет от 1 до 200 — окно {@code priority × K}
     * при шаге дедлайна в минуту. Случайные значения считаются на каждую строку, а не один раз на запрос.
     */
    private static final String DEFERRED = """
            INSERT INTO tc_task (
                id, created_at, updated_at, execution_id, execution_created_at, task_type, payload, status,
                priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
            SELECT gen_random_uuid(), now(), now(), gen_random_uuid(), :executionCreatedAt, :type, '{}'::jsonb,
                   'PENDING', r.priority, r.at, r.at + make_interval(secs => r.priority * 60), now()
              FROM (SELECT now() + interval '1 hour' + random() * interval '2 hours' AS at,
                           1 + floor(random() * 200)::int AS priority
                      FROM generate_series(1, :count)) r
            """;

    private static final String ORPHANS = """
            INSERT INTO tc_task_unit (
                id, created_at, updated_at, execution_id, execution_created_at, task_id, parent_attempt_id,
                task_type, payload, status, priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
            SELECT gen_random_uuid(), now(), now(), gen_random_uuid(), :executionCreatedAt, gen_random_uuid(),
                   gen_random_uuid(), 'orphan', '{}'::jsonb, 'PENDING', 100, r.at,
                   r.at + make_interval(secs => 100 * 60), now()
              FROM generate_series(1, :count) g
              CROSS JOIN LATERAL (SELECT now() - interval '3 hours' + make_interval(secs => g * 0.001) AS at) r
            """;

    @Test
    void claimOfLiveTypeDegradesAtMostTwiceUnderInterference() {
        final Run baseline = this.run(false);
        report("базовый", baseline);
        final Run interfered = this.run(true);
        report("с помехами", interfered);
        report("отношение", baseline, interfered);

        assertThat(baseline.ticks()).hasSize(LIVE_TASKS / TICK);
        assertThat(interfered.ticks()).hasSize(LIVE_TASKS / TICK);

        assertThat(interfered.medianMillis()).isLessThanOrEqualTo(baseline.medianMillis() * MAX_DEGRADATION);
        assertThat(interfered.p95Millis()).isLessThanOrEqualTo(baseline.p95Millis() * MAX_DEGRADATION);
        assertThat(interfered.drainMillis()).isLessThanOrEqualTo(baseline.drainMillis() * MAX_DEGRADATION);
    }

    private Run run(final boolean interference) {
        final long setup = System.nanoTime();
        final TaskTestDatabase db = TaskTestDatabase.createPooled();
        if (interference) {
            final Instant current = db.currentMonth;
            for (int month = MONTHS_BACK; month >= 1; month--) {
                db.createMonth(
                        current.atOffset(ZoneOffset.UTC).minusMonths(month).toInstant());
            }
        }
        final Instant partition = db.now();
        insert(db.jdbc, TASKS, partition, LIVE, LIVE_TASKS, 3600);
        if (interference) {
            insert(db.jdbc, ORPHANS, partition, "orphan", ORPHAN_UNITS, 0);
            insert(db.jdbc, TASKS, partition, "lagging", LAGGING_TASKS, 7200);
            insert(db.jdbc, DEFERRED, partition, LIVE, DEFERRED_TASKS, 0);
        }
        db.jdbc.sql("ANALYZE").update();
        db.advanceHorizon();
        phase(interference, "подготовка", setup);
        final long measuring = System.nanoTime();
        final PlanCost plan = interference ? this.planCost(db) : null;
        phase(interference, "замер плана", measuring);
        return drain(db, plan);
    }

    /**
     * Опустошает очередь живого типа тактами; после каждого такта захваченное закрывается, как
     * у воркеров (docs/adr/0130-load-scenario-closes-claimed-work.md), вне замера такта и без учёта
     * в опустошении.
     */
    private static Run drain(final TaskTestDatabase db, final PlanCost plan) {
        final TaskClaimer claimer = db.claimer("load", SETTINGS, () -> 0.5);
        final TaskAttemptCloser closer = db.closer(SETTINGS);
        final List<Long> ticks = new ArrayList<>();
        List<ClaimedAttempt> claimed = List.of();
        long closing = 0;
        final long started = System.nanoTime();
        do {
            final long tick = System.nanoTime();
            claimed = claimer.claim(MAIN, TYPES, TICK);
            if (!claimed.isEmpty()) {
                ticks.add(System.nanoTime() - tick);
            }
            final long closed = System.nanoTime();
            claimed.forEach(attempt -> closer.succeed(attempt, null));
            closing += System.nanoTime() - closed;
        } while (!claimed.isEmpty());
        final long drain = System.nanoTime() - started - closing;
        return new Run(ticks, drain, plan);
    }

    /** Запрос такта подряд при {@code force_custom_plan} и при умолчании; каждая транзакция откатывается. */
    private PlanCost planCost(final TaskTestDatabase db) {
        final List<Long> custom = new ArrayList<>();
        final List<Long> auto = new ArrayList<>();
        for (int i = 0; i < PLAN_EXECUTIONS; i++) {
            custom.add(this.executeTick(db, "force_custom_plan"));
            auto.add(this.executeTick(db, "auto"));
        }
        return new PlanCost(median(custom), median(auto));
    }

    private long executeTick(final TaskTestDatabase db, final String mode) {
        final List<String> types = List.of(LIVE);
        final String query = TaskClaimQuery.branches(TaskClaimer.Ordering.DEADLINE, types);
        final Long nanos = new TransactionTemplate(db.transactionManager).execute(status -> {
            db.jdbc
                    .sql("SELECT set_config('plan_cache_mode', :mode, true)")
                    .param("mode", mode)
                    .query(String.class)
                    .single();
            final TaskPartitionHorizon.ActiveRange range = TaskPartitionHorizon.activeRange(db.jdbc);
            final long start = System.nanoTime();
            TaskClaimQuery.bind(db.jdbc.sql(query), range, types, TICK, TICK)
                    .query()
                    .listOfRows();
            final long elapsed = System.nanoTime() - start;
            status.setRollbackOnly();
            return elapsed;
        });
        return nanos == null ? 0 : nanos;
    }

    private static void insert(
            final JdbcClient jdbc,
            final String sql,
            final Instant partition,
            final String type,
            final int count,
            final int ageSeconds) {
        final JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("executionCreatedAt", TaskSql.parameter(partition))
                .param("count", count);
        if (sql.contains(":type")) {
            statement.param("type", type);
        }
        if (sql.contains(":ageSeconds")) {
            statement.param("ageSeconds", ageSeconds);
        }
        statement.update();
    }

    private static long median(final List<Long> values) {
        return percentile(values, 0.5);
    }

    private static long percentile(final List<Long> values, final double share) {
        final List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(share * sorted.size())));
    }

    private static void phase(final boolean interference, final String name, final long startedNanos) {
        System.out.printf(
                Locale.ROOT,
                "[loadTest] %s, %s: %.1f с%n",
                interference ? "с помехами" : "базовый",
                name,
                (System.nanoTime() - startedNanos) / 1e9);
    }

    private static void report(final String name, final Run run) {
        System.out.printf(
                Locale.ROOT,
                "[loadTest] %s: тактов %d, медиана %.2f мс, p95 %.2f мс, опустошение %.0f мс%n",
                name,
                run.ticks().size(),
                run.medianMillis(),
                run.p95Millis(),
                run.drainMillis());
        if (run.plan() != null) {
            System.out.printf(
                    Locale.ROOT,
                    "[loadTest] force_custom_plan: медиана запроса такта %.2f мс; умолчание PostgreSQL: %.2f мс%n",
                    run.plan().customNanos() / 1e6,
                    run.plan().autoNanos() / 1e6);
        }
    }

    private static void report(final String name, final Run baseline, final Run interfered) {
        System.out.printf(
                Locale.ROOT,
                "[loadTest] %s: медиана %.2f×, p95 %.2f×, опустошение %.2f× (порог %.1f×)%n",
                name,
                interfered.medianMillis() / baseline.medianMillis(),
                interfered.p95Millis() / baseline.p95Millis(),
                interfered.drainMillis() / baseline.drainMillis(),
                MAX_DEGRADATION);
    }

    /**
     * Итог прогона.
     *
     * @param ticks длительности тактов, в которых что-то захвачено, в наносекундах
     * @param drainNanos время опустошения очереди живого типа без времени закрытия захваченного
     * @param plan замер {@code force_custom_plan} или {@code null}
     */
    private record Run(List<Long> ticks, long drainNanos, PlanCost plan) {

        double medianMillis() {
            return median(this.ticks) / 1e6;
        }

        double p95Millis() {
            return percentile(this.ticks, 0.95) / 1e6;
        }

        double drainMillis() {
            return this.drainNanos / 1e6;
        }
    }

    /**
     * Медианы запроса такта при двух режимах кеша планов.
     *
     * @param customNanos при {@code force_custom_plan}
     * @param autoNanos при умолчании
     */
    private record PlanCost(long customNanos, long autoNanos) {}
}
