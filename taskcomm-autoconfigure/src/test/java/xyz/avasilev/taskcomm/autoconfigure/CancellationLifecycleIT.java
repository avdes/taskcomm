package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import xyz.avasilev.taskcomm.task.api.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.Emitter;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskCancelResult;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.TaskSubmitOptions;
import xyz.avasilev.taskcomm.task.api.TaskUnit;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Отмена на любой сущности через публичный API и настоящий движок
 * (docs/adr/0070-cancellation-on-any-entity.md, docs/adr/0100-cancel-keeps-real-outcome.md):
 * не начинавшаяся — {@code CANCELLED} сразу; работающая доигрывает и пишет настоящий исход,
 * {@code shouldStop()} узнаёт об отмене; неуспех после отмены не повторяется; отмена во время
 * раскрытия и во время работы детей; отмена одной подзадачи; отказ у терминальной и уже отменённой.
 */
class CancellationLifecycleIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

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
                    "taskcomm.task.poll.workers=1",
                    "taskcomm.task.lease.duration=6s",
                    "taskcomm.task.lease.heartbeat-interval=500ms",
                    "taskcomm.task.lease.max-execution-time=1m",
                    "taskcomm.task.retry.max-attempts=5",
                    "taskcomm.task.retry.initial-delay=0s",
                    "taskcomm.task.retry.multiplier=1",
                    "taskcomm.task.retry.max-delay=0s");

    @Test
    void notStartedTaskIsCancelledAtOnceAndSecondCancelIsRejected() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<String> task = tasks.submit(
                    "slow",
                    "later",
                    TaskSubmitOptions.defaults().notBefore(Instant.now().plusSeconds(3600)));

            final TaskCancelResult first = tasks.cancel(task);
            final TaskCancelResult second = tasks.cancel(task);

            assertThat(first).isInstanceOf(TaskCancelResult.Accepted.class);
            assertThat(first.reason()).contains("сразу");
            assertThat(status(context.getBean(DataSource.class), "tc_task", task.id()))
                    .isEqualTo("CANCELLED");
            assertThat(second).isInstanceOf(TaskCancelResult.Rejected.class);
            assertThat(second.reason()).contains("уже отменена");
        });
    }

    @Test
    void terminalTaskIsNotCancelled() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<String> task = tasks.submit("slow", "quick");
            awaitStatus(context.getBean(DataSource.class), "tc_task", task.id(), "SUCCEEDED");

            final TaskCancelResult result = tasks.cancel(task);

            assertThat(result.accepted()).isFalse();
            assertThat(result.reason()).contains("SUCCEEDED");
            assertThat(cancelRequested(context.getBean(DataSource.class), "tc_task", task.id()))
                    .isFalse();
        });
    }

    @Test
    void runningTaskSeesShouldStopAndKeepsItsRealSuccess() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final SlowProcessor slow = context.getBean(SlowProcessor.class);
            final Task<String> task = tasks.submit("slow", "wait");
            assertThat(slow.started.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            final TaskCancelResult result = tasks.cancel(task);

            assertThat(result.accepted()).isTrue();
            assertThat(result.reason()).contains("доиграет");
            awaitStatus(context.getBean(DataSource.class), "tc_task", task.id(), "SUCCEEDED");
            assertThat(slow.sawStop).containsEntry(task.id(), true);
        });
    }

    @Test
    void failureAfterCancelIsNotRetried() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final SlowProcessor slow = context.getBean(SlowProcessor.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<String> task = tasks.submit("slow", "wait-then-fail");
            assertThat(slow.started.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            tasks.cancel(task);

            awaitStatus(dataSource, "tc_task", task.id(), "CANCELLED");
            assertThat(description(dataSource, "tc_task", task.id())).isEqualTo("отменено");
            assertThat(JdbcClient.create(dataSource)
                            .sql("SELECT count(*) FROM tc_task_attempt WHERE target_id = :id")
                            .param("id", task.id())
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        });
    }

    @Test
    void cancelDuringExpansionSkipsChildrenAndRunsNoCollect() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final OrderProcessor orders = context.getBean(OrderProcessor.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<String> task = tasks.submit("orders", "hold-expand");
            assertThat(orders.expanding.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();

            tasks.cancel(task);
            orders.releaseExpand.countDown();

            awaitStatus(dataSource, "tc_task", task.id(), "CANCELLED");
            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(count(
                                    dataSource,
                                    "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'SKIPPED'",
                                    task.id()))
                            .isEqualTo(3));
            assertThat(orders.collected).doesNotContainKey(task.id());
        });
    }

    @Test
    void cancelWhileChildrenWorkSkipsWaitingOnesAndCancelsTaskWhenRunningOnesEnd() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final OrderProcessor orders = context.getBean(OrderProcessor.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<String> task = tasks.submit("orders", "hold-unit");
            assertThat(orders.unitRunning.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();

            final TaskCancelResult result = tasks.cancel(task);
            orders.releaseUnit.countDown();

            assertThat(result.reason()).contains("потомки");
            awaitStatus(dataSource, "tc_task", task.id(), "CANCELLED");
            assertThat(count(
                            dataSource,
                            "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'SUCCEEDED'",
                            task.id()))
                    .isEqualTo(1);
            assertThat(count(
                            dataSource,
                            "SELECT count(*) FROM tc_task_unit WHERE task_id = :id AND status = 'SKIPPED'",
                            task.id()))
                    .isEqualTo(2);
            assertThat(orders.collected).doesNotContainKey(task.id());
        });
    }

    @Test
    void cancelledUnitCountsAsFailedInAggregate() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final OrderProcessor orders = context.getBean(OrderProcessor.class);
            final DataSource dataSource = context.getBean(DataSource.class);
            final Task<String> task = tasks.submit("orders", "cancel-one");
            assertThat(orders.unitRunning.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
            final TaskUnit<Integer> running = orders.held;

            final TaskCancelResult result = tasks.cancel(running);

            assertThat(result.accepted()).isTrue();
            awaitStatus(dataSource, "tc_task", task.id(), "WARNING");
            assertThat(status(dataSource, "tc_task_unit", running.id())).isEqualTo("CANCELLED");
            assertThat(cancelRequested(dataSource, "tc_task", task.id())).isFalse();
        });
    }

    private static void awaitStatus(
            final DataSource dataSource, final String table, final UUID id, final String status) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(status(dataSource, table, id)).isEqualTo(status));
    }

    private static String status(final DataSource dataSource, final String table, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT status FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static String description(final DataSource dataSource, final String table, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT status_description FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static boolean cancelRequested(final DataSource dataSource, final String table, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT cancel_requested_at IS NOT NULL FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private static int count(final DataSource dataSource, final String sql, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql(sql)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    /**
     * Плоская задача: {@code wait} ждёт сигнала прекращения и возвращается успехом,
     * {@code wait-then-fail} ждёт его и падает.
     */
    static class SlowProcessor implements FlatTaskProcessor<String, String> {

        final CountDownLatch started = new CountDownLatch(1);

        final Map<UUID, Boolean> sawStop = new ConcurrentHashMap<>();

        @Override
        public String taskType() {
            return "slow";
        }

        @Override
        public String process(final Task<String> task, final ProcessingContext context) {
            if (task.payload().startsWith("wait")) {
                this.started.countDown();
                await().atMost(WAIT).until(context::shouldStop);
                this.sawStop.put(task.id(), true);
                if (task.payload().endsWith("fail")) {
                    throw new IllegalStateException("прервано по отмене");
                }
            }
            return "готово";
        }
    }

    /**
     * Задача с тремя подзадачами. {@code hold-expand} ждёт сигнала посреди раскрытия;
     * {@code hold-unit} держит первую подзадачу, пока тест не отпустит; у {@code cancel-one} первая
     * подзадача ждёт сигнала прекращения и падает. Воркер один: пока первая работает, остальные
     * в {@code PENDING}.
     */
    static class OrderProcessor implements UnitTaskProcessor<String, Integer, Integer, Integer> {

        final CountDownLatch expanding = new CountDownLatch(1);

        final CountDownLatch releaseExpand = new CountDownLatch(1);

        final CountDownLatch unitRunning = new CountDownLatch(1);

        final CountDownLatch releaseUnit = new CountDownLatch(1);

        final Map<UUID, Integer> collected = new ConcurrentHashMap<>();

        volatile TaskUnit<Integer> held;

        @Override
        public String taskType() {
            return "orders";
        }

        @Override
        public void expand(final Task<String> task, final Emitter<Integer> units, final ProcessingContext context) {
            final int offset = task.payload().equals("cancel-one") ? 100 : 0;
            for (int i = 1; i <= 3; i++) {
                units.emit(offset + i);
            }
            if (task.payload().equals("hold-expand")) {
                this.expanding.countDown();
                hold(this.releaseExpand);
            }
        }

        @Override
        public Integer process(final TaskUnit<Integer> unit, final ProcessingContext context) {
            if (unit.payload() == 1) {
                this.held = unit;
                this.unitRunning.countDown();
                hold(this.releaseUnit);
            } else if (unit.payload() == 101) {
                this.held = unit;
                this.unitRunning.countDown();
                await().atMost(WAIT).until(context::shouldStop);
                throw new IllegalStateException("подзадача прервана по отмене");
            }
            return unit.payload();
        }

        @Override
        public Integer collect(
                final Task<String> task,
                final Stream<CollectedUnit<Integer, Integer>> units,
                final ProcessingContext context) {
            this.collected.merge(task.id(), 1, Integer::sum);
            return (int) units.count();
        }

        private static void hold(final CountDownLatch latch) {
            try {
                assertThat(latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
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
        SlowProcessor slowProcessor() {
            return new SlowProcessor();
        }

        @Bean
        OrderProcessor orderProcessor() {
            return new OrderProcessor();
        }
    }
}
