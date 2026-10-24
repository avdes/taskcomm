package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.CollectedBatch;
import xyz.avasilev.taskcomm.task.api.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.Emitter;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskBatchStatus;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.TaskUnit;
import xyz.avasilev.taskcomm.task.api.TaskUnitStatus;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Задачи с подзадачами и с группами проходят каскад до терминального статуса через публичный API
 * и настоящий движок: раскрытие несколькими сбросами буфера, обработка детей, сборка по ленивому
 * потоку в несколько страниц, агрегат детей, повтор раскрытия, пропуск раскрытия, неуспешная
 * сборка, удаление результатов детей после сборки.
 */
class ExpandCollectLifecycleIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommTaskAutoConfiguration.class))
            .withUserConfiguration(Database.class, Processors.class)
            .withBean(String.class, TestPostgres::newSchema)
            .withPropertyValues(
                    "taskcomm.schema.initialize=always",
                    "taskcomm.task.poll.interval=100ms",
                    "taskcomm.task.poll.workers=16",
                    "taskcomm.task.lease.duration=6s",
                    "taskcomm.task.lease.heartbeat-interval=1s",
                    "taskcomm.task.lease.max-execution-time=1m",
                    "taskcomm.task.retry.max-attempts=3",
                    "taskcomm.task.retry.initial-delay=0s",
                    "taskcomm.task.retry.multiplier=1",
                    "taskcomm.task.retry.max-delay=0s",
                    "taskcomm.results.discard=immediately");

    @Test
    void unitTaskExpandsProcessesAndCollectsAcrossSeveralFlushesAndPages() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<Order> task = tasks.submit("orders", Order.of("big", 1500));

            awaitStatus(dataSource, task.id(), "SUCCEEDED");
            assertThat(tasks.result(task, Report.class)).contains(new Report(1500, 0, 0, 10L * 1500 * 1501 / 2));
            assertThat(count(
                            dataSource,
                            "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'SUCCEEDED'",
                            task.id()))
                    .isEqualTo(1500);
            awaitNoUnitResults(dataSource, task.id());
            assertThat(discardedAt(dataSource, "tc_task", task.id())).isTrue();
            assertThat(phase(dataSource, task.id())).isEqualTo("COLLECT");
        });
    }

    @Test
    void expansionWithoutChildrenStillRunsCollectWithEmptyStream() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<Order> task = tasks.submit("orders", Order.of("empty", 0));

            awaitStatus(context.getBean(DataSource.class), task.id(), "SUCCEEDED");
            assertThat(tasks.result(task, Report.class)).contains(new Report(0, 0, 0, 0));
        });
    }

    @Test
    void aggregateOfChildrenDecidesParentStatus() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<Order> partly = tasks.submit("orders", new Order("partly", 3, Set.of(1), Set.of(2)));
            final Task<Order> allFailed = tasks.submit("orders", new Order("all-failed", 2, Set.of(1, 2), Set.of()));
            final Task<Order> allSkipped = tasks.submit("orders", new Order("all-skipped", 2, Set.of(), Set.of(1, 2)));
            final Task<Order> failedAndSkipped =
                    tasks.submit("orders", new Order("failed-and-skipped", 2, Set.of(1), Set.of(2)));
            final Task<Order> doneAndSkipped =
                    tasks.submit("orders", new Order("done-and-skipped", 2, Set.of(), Set.of(2)));

            awaitStatus(dataSource, partly.id(), "WARNING");
            awaitStatus(dataSource, allFailed.id(), "FAILED");
            awaitStatus(dataSource, allSkipped.id(), "SKIPPED");
            awaitStatus(dataSource, failedAndSkipped.id(), "FAILED");
            awaitStatus(dataSource, doneAndSkipped.id(), "SUCCEEDED");
            assertThat(tasks.result(partly, Report.class)).contains(new Report(1, 1, 1, 50));
        });
    }

    @Test
    void retriedExpansionAbandonsChildrenOfFailedAttemptAndCollectsOnlyLatest() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<Order> task = tasks.submit("orders", Order.of("flaky-expand", 3));

            awaitStatus(dataSource, task.id(), "SUCCEEDED");
            assertThat(tasks.result(task, Report.class)).contains(new Report(3, 0, 0, 60));
            assertThat(JdbcClient.create(dataSource)
                            .sql("SELECT DISTINCT status_description FROM tc_task_unit WHERE task_id = :id"
                                    + " AND status = 'ABANDONED'")
                            .param("id", task.id())
                            .query(String.class)
                            .list())
                    .singleElement()
                    .satisfies(reason ->
                            assertThat(reason).startsWith("попытка раскрытия ").endsWith(" неуспешна"));
            assertThat(count(
                            dataSource,
                            "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'ABANDONED'",
                            task.id()))
                    .isEqualTo(1000);
        });
    }

    @Test
    void skippedExpansionSkipsTaskAbandonsChildrenAndRunsNoCollect() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<Order> task = tasks.submit("orders", Order.of("skip-expand", 1001));

            awaitStatus(dataSource, task.id(), "SKIPPED");
            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(count(
                                    dataSource,
                                    "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'ABANDONED'",
                                    task.id()))
                            .isEqualTo(1000));
            assertThat(context.getBean(OrderProcessor.class).collected).doesNotContainKey(task.id());
            assertThat(phase(dataSource, task.id())).isEqualTo("EXPAND");
        });
    }

    @Test
    void failingCollectExhaustsItsOwnLimitAndFailsTaskDespiteSucceededChildren() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<Order> task = tasks.submit("orders", Order.of("fail-collect", 2));

            awaitStatus(dataSource, task.id(), "FAILED");
            assertThat(JdbcClient.create(dataSource)
                            .sql("SELECT status_description FROM tc_task WHERE id = :id")
                            .param("id", task.id())
                            .query(String.class)
                            .single())
                    .isEqualTo("сборка неуспешна: попытки исчерпаны");
            assertThat(count(
                            dataSource,
                            "SELECT count(*) FROM tc_task_attempt WHERE target_id = :id AND phase = 'COLLECT'",
                            task.id()))
                    .isEqualTo(3);
            assertThat(unitResults(dataSource, task.id())).isEqualTo(2);
            assertThat(discardedAt(dataSource, "tc_task", task.id())).isFalse();
        });
    }

    @Test
    void skippedCollectSkipsTaskOverAggregateKeepsResultAndDiscardsChildResults() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<Order> task = tasks.submit("orders", Order.of("skip-collect", 2));

            awaitStatus(dataSource, task.id(), "SKIPPED");
            assertThat(JdbcClient.create(dataSource)
                            .sql("SELECT status_description FROM tc_task WHERE id = :id")
                            .param("id", task.id())
                            .query(String.class)
                            .single())
                    .isEqualTo("свод не нужен");
            assertThat(tasks.result(task, Report.class)).contains(new Report(2, 0, 0, 30));
            awaitNoUnitResults(dataSource, task.id());
            assertThat(discardedAt(dataSource, "tc_task", task.id())).isTrue();
        });
    }

    @Test
    void emitterOutsideItsCallIsRejected() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<Order> task = tasks.submit("orders", Order.of("keep-emitter", 1));

            awaitStatus(context.getBean(DataSource.class), task.id(), "SUCCEEDED");
            final Emitter<Line> kept = context.getBean(OrderProcessor.class).kept;
            assertThatThrownBy(() -> kept.emit(new Line(42, false, false)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("только внутри вызова раскрытия");
        });
    }

    @Test
    void batchTaskExpandsIntoGroupsCollectsEachGroupThenTask() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<List<String>> task = tasks.submit("prices", List.of("a", "b"));

            awaitStatus(dataSource, task.id(), "SUCCEEDED");
            assertThat(tasks.result(task, PriceReport.class))
                    .contains(new PriceReport(List.of(new SupplierReport("a", 3, 6), new SupplierReport("b", 3, 6))));
            final List<UUID> batches = JdbcClient.create(dataSource)
                    .sql("SELECT id FROM tc_task_batch WHERE task_id = :id AND status = 'SUCCEEDED'")
                    .param("id", task.id())
                    .query(UUID.class)
                    .list();
            assertThat(batches).hasSize(2);
            for (final UUID batch : batches) {
                assertThat(discardedAt(dataSource, "tc_task_batch", batch)).isTrue();
            }
            await().atMost(WAIT).until(() -> discardedAt(dataSource, "tc_task", task.id()));
            assertThat(count(
                            dataSource,
                            "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND batch_id IS NOT NULL",
                            task.id()))
                    .isEqualTo(6);
            awaitNoUnitResults(dataSource, task.id());
        });
    }

    private static void awaitStatus(final DataSource dataSource, final UUID id, final String status) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(JdbcClient.create(dataSource)
                                .sql("SELECT status FROM tc_task WHERE id = :id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                        .isEqualTo(status));
    }

    /** Результаты детей удаляются после коммита сборки — своими транзакциями, чуть позже статуса. */
    private static void awaitNoUnitResults(final DataSource dataSource, final UUID taskId) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(unitResults(dataSource, taskId)).isZero());
    }

    private static int count(final DataSource dataSource, final String sql, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql(sql)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private static int unitResults(final DataSource dataSource, final UUID taskId) {
        return count(
                dataSource,
                "SELECT count(*) FROM tc_task_result r JOIN tc_task_unit u ON u.id = r.target_id WHERE u.task_id = :id",
                taskId);
    }

    private static boolean discardedAt(final DataSource dataSource, final String table, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT child_results_discarded_at IS NOT NULL FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private static String phase(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT phase FROM tc_task WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    /**
     * Параметры задачи с подзадачами: подзадачи — числа от 1 до {@code count}.
     *
     * @param name сценарий
     * @param count сколько подзадач
     * @param fail какие подзадачи падают без повтора
     * @param skip какие подзадачи пропускаются
     */
    record Order(String name, int count, Set<Integer> fail, Set<Integer> skip) {

        static Order of(final String name, final int count) {
            return new Order(name, count, Set.of(), Set.of());
        }
    }

    /**
     * Свод по задаче с подзадачами.
     *
     * @param succeeded выполнено
     * @param failed упало
     * @param skipped пропущено
     * @param sum сумма результатов
     */
    record Report(int succeeded, int failed, int skipped, long sum) {}

    /**
     * Подзадача: число и что с ним делать.
     *
     * @param value число
     * @param fail упасть без повтора
     * @param skip пропустить
     */
    record Line(int value, boolean fail, boolean skip) {}

    /**
     * Товар группы.
     *
     * @param supplier поставщик
     * @param price цена
     */
    record Item(String supplier, int price) {}

    /**
     * Свод группы.
     *
     * @param supplier поставщик
     * @param items сколько товаров
     * @param sum сумма цен
     */
    record SupplierReport(String supplier, int items, int sum) {}

    /**
     * Свод задачи с группами.
     *
     * @param suppliers своды групп по поставщикам
     */
    record PriceReport(List<SupplierReport> suppliers) {}

    /** Задача с подзадачами: результат подзадачи — её число, умноженное на десять. */
    static class OrderProcessor implements UnitTaskProcessor<Order, Line, Integer, Report> {

        final Map<UUID, Integer> collected = new ConcurrentHashMap<>();

        volatile Emitter<Line> kept;

        private final Map<UUID, AtomicInteger> expansions = new ConcurrentHashMap<>();

        @Override
        public String taskType() {
            return "orders";
        }

        @Override
        public void expand(final Task<Order> task, final Emitter<Line> units, final ProcessingContext context) {
            final Order order = task.payload();
            final int attempt = this.expansions
                    .computeIfAbsent(task.id(), id -> new AtomicInteger())
                    .incrementAndGet();
            if (order.name().equals("flaky-expand") && attempt == 1) {
                // больше одного сброса буфера: в базу попадает первая тысяча, остаток выброшен вместе с буфером
                for (int i = 1; i <= 1005; i++) {
                    units.emit(new Line(10_000 + i, false, false));
                }
                throw new IllegalStateException("источник отвалился посреди перечисления");
            }
            for (int i = 1; i <= order.count(); i++) {
                units.emit(new Line(i, order.fail().contains(i), order.skip().contains(i)));
            }
            if (order.name().equals("skip-expand")) {
                context.skip("заказов не нужно");
            }
            if (order.name().equals("keep-emitter")) {
                this.kept = units;
            }
        }

        @Override
        public Integer process(final TaskUnit<Line> unit, final ProcessingContext context) {
            assertThat(unit.taskType()).isEqualTo("orders");
            assertThat(unit.batchId()).isEmpty();
            final Line line = unit.payload();
            if (line.fail()) {
                context.doNotRetry("подзадача " + line.value() + " битая");
                throw new IllegalArgumentException("подзадача " + line.value() + " не обрабатывается");
            }
            if (line.skip()) {
                context.skip("подзадача " + line.value() + " не нужна");
            }
            return line.value() * 10;
        }

        @Override
        public Report collect(
                final Task<Order> task,
                final Stream<CollectedUnit<Line, Integer>> units,
                final ProcessingContext context) {
            this.collected.merge(task.id(), 1, Integer::sum);
            if (task.payload().name().equals("fail-collect")) {
                throw new IllegalStateException("свод не собрался");
            }
            if (task.payload().name().equals("skip-collect")) {
                context.skip("свод не нужен");
            }
            final int[] counts = new int[3];
            final long[] sum = new long[1];
            units.forEach(unit -> {
                switch (unit.status()) {
                    case SUCCEEDED -> counts[0]++;
                    case FAILED -> counts[1]++;
                    case SKIPPED -> counts[2]++;
                    default -> throw new IllegalStateException("в потоке сборки нетерминальный " + unit.status());
                }
                if (unit.result() != null) {
                    sum[0] += unit.result();
                }
            });
            return new Report(counts[0], counts[1], counts[2], sum[0]);
        }
    }

    /** Задача с группами: группа — поставщик, в ней три товара с ценами 1, 2, 3. */
    static class PriceProcessor
            implements BatchTaskProcessor<List<String>, String, Item, Integer, SupplierReport, PriceReport> {

        @Override
        public String taskType() {
            return "prices";
        }

        @Override
        public void expand(
                final Task<List<String>> task, final Emitter<String> batches, final ProcessingContext context) {
            task.payload().forEach(batches::emit);
        }

        @Override
        public void expandBatch(
                final TaskBatch<String> batch, final Emitter<Item> units, final ProcessingContext context) {
            assertThat(batch.status()).isEqualTo(TaskBatchStatus.RUNNING);
            for (int price = 1; price <= 3; price++) {
                units.emit(new Item(batch.payload(), price));
            }
        }

        @Override
        public Integer process(final TaskUnit<Item> unit, final ProcessingContext context) {
            assertThat(unit.batchId()).isPresent();
            assertThat(unit.status()).isEqualTo(TaskUnitStatus.RUNNING);
            return unit.payload().price();
        }

        @Override
        public SupplierReport collectBatch(
                final TaskBatch<String> batch,
                final Stream<CollectedUnit<Item, Integer>> units,
                final ProcessingContext context) {
            final List<Integer> prices = units.map(CollectedUnit::result).toList();
            return new SupplierReport(
                    batch.payload(),
                    prices.size(),
                    prices.stream().mapToInt(Integer::intValue).sum());
        }

        @Override
        public PriceReport collect(
                final Task<List<String>> task,
                final Stream<CollectedBatch<String, SupplierReport>> batches,
                final ProcessingContext context) {
            final List<SupplierReport> reports =
                    new ArrayList<>(batches.map(CollectedBatch::result).toList());
            reports.sort((left, right) -> left.supplier().compareTo(right.supplier()));
            return new PriceReport(reports);
        }
    }

    /** {@code DataSource} на схеме теста. */
    @Configuration(proxyBeanMethods = false)
    static class Database {

        @Bean
        @Primary
        DataSource dataSource(final String schema) {
            return TestPostgres.dataSource(schema);
        }

        @Bean
        DataSourceTransactionManager transactionManager(final DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    /** Процессоры теста. */
    @Configuration(proxyBeanMethods = false)
    static class Processors {

        @Bean
        OrderProcessor orderProcessor() {
            return new OrderProcessor();
        }

        @Bean
        PriceProcessor priceProcessor() {
            return new PriceProcessor();
        }
    }
}
