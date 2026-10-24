package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskPriority;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.TaskStatus;

/**
 * Плоская задача проходит путь до терминального статуса через публичный API и настоящий движок:
 * успех с результатом, исчерпание попыток, запрет повтора, пропуск, постановка из процессора
 * в то же дело с указателем на создателя, номер попытки и признак повтора в контексте.
 */
class FlatTaskLifecycleIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

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
                    "taskcomm.task.lease.duration=6s",
                    "taskcomm.task.lease.heartbeat-interval=1s",
                    "taskcomm.task.lease.max-execution-time=1m",
                    "taskcomm.task.retry.max-attempts=3",
                    "taskcomm.task.retry.initial-delay=0s",
                    "taskcomm.task.retry.multiplier=1",
                    "taskcomm.task.retry.max-delay=0s");

    @Test
    void succeededTaskKeepsResultReadableThroughService() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<Period> task = tasks.submit(ReportProcessor.class, new Period("2026-03"));

            assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
            assertThat(task.priority()).isEqualTo(30);
            awaitStatus(context.getBean(DataSource.class), task.id(), "SUCCEEDED");
            assertThat(tasks.result(task, Summary.class)).contains(new Summary("2026-03", 1));
            assertThat(tasks.findInExecution(task.executionRef(), ReportProcessor.class))
                    .singleElement()
                    .satisfies(found -> {
                        assertThat(found.payload()).isEqualTo(new Period("2026-03"));
                        assertThat(found.status()).isEqualTo(TaskStatus.SUCCEEDED);
                    });
        });
    }

    @Test
    void failingTaskIsRetriedUntilLimitThenFails() {
        this.contextRunner.run(context -> {
            final Task<String> task = context.getBean(TaskService.class).submit("flaky", "x");

            awaitStatus(context.getBean(DataSource.class), task.id(), "FAILED");
            assertThat(description(context.getBean(DataSource.class), task.id()))
                    .isEqualTo("попытки исчерпаны");
            assertThat(context.getBean(FlakyProcessor.class).attempts).containsExactly(1, 2, 3);
            assertThat(context.getBean(FlakyProcessor.class).retries).containsExactly(false, true, true);
            assertThat(attemptStatuses(context.getBean(DataSource.class), task.id()))
                    .containsExactly("FAILED", "FAILED", "FAILED");
        });
    }

    @Test
    void doNotRetryFailsAfterFirstAttemptWithItsReason() {
        this.contextRunner.run(context -> {
            final Task<String> task = context.getBean(TaskService.class).submit("broken", "x");

            awaitStatus(context.getBean(DataSource.class), task.id(), "FAILED");
            assertThat(description(context.getBean(DataSource.class), task.id()))
                    .isEqualTo("повторять запрещено: битая нагрузка");
            assertThat(attemptStatuses(context.getBean(DataSource.class), task.id()))
                    .containsExactly("FAILED");
        });
    }

    @Test
    void skipEndsTaskSkippedAndStillKeepsReturnedValue() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<String> task = tasks.submit("skipping", "x");

            awaitStatus(context.getBean(DataSource.class), task.id(), "SKIPPED");
            assertThat(description(context.getBean(DataSource.class), task.id()))
                    .isEqualTo("нечего делать");
            assertThat(tasks.result(task)).contains("частично");
        });
    }

    @Test
    void taskSubmittedFromProcessorLandsInSameExecutionWithOrigin() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<Period> parent = tasks.submit("report", new Period("spawn"));

            awaitStatus(context.getBean(DataSource.class), parent.id(), "SUCCEEDED");
            final List<Task<Period>> inExecution = tasks.findInExecution(parent.executionRef(), ReportProcessor.class);
            assertThat(inExecution).hasSize(2);
            final Task<Period> child = inExecution.stream()
                    .filter(task -> !task.id().equals(parent.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(child.payload()).isEqualTo(new Period("child"));
            assertThat(child.origin()).hasValueSatisfying(origin -> {
                assertThat(origin.type()).isEqualTo(OriginType.TASK);
                assertThat(origin.id()).isEqualTo(parent.id());
                assertThat(origin.relation()).isEqualTo(OriginRelation.SPAWNED);
                assertThat(origin.attemptId()).isNotNull();
            });
        });
    }

    @Test
    void explicitOtherExecutionDropsOrigin() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);
            final Task<Period> other = tasks.submit("report", new Period("2026-01"));
            final ExecutionRef otherExecution = other.executionRef();
            context.getBean(ReportProcessor.class).target = otherExecution;
            final Task<Period> parent = tasks.submit("report", new Period("spawn-elsewhere"));

            awaitStatus(context.getBean(DataSource.class), parent.id(), "SUCCEEDED");
            assertThat(tasks.findInExecution(otherExecution, ReportProcessor.class))
                    .filteredOn(task -> task.payload().equals(new Period("child")))
                    .singleElement()
                    .satisfies(child -> assertThat(child.origin()).isEmpty());
        });
    }

    @Test
    void submitRejectsUnknownAndDeprecatedTypes() {
        this.contextRunner.run(context -> {
            final TaskService tasks = context.getBean(TaskService.class);

            assertThatThrownBy(() -> tasks.submit("no-such-type", "x"))
                    .isInstanceOf(TaskcommException.class)
                    .hasMessageContaining("'no-such-type'");
            assertThatThrownBy(() -> tasks.submit("legacy", "x"))
                    .isInstanceOf(TaskcommException.class)
                    .hasMessageContaining("устарел");
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

    private static String description(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT status_description FROM tc_task WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static List<String> attemptStatuses(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT status FROM tc_task_attempt WHERE target_id = :id ORDER BY started_at")
                .param("id", id)
                .query(String.class)
                .list();
    }

    /**
     * Параметры отчёта.
     *
     * @param month месяц
     */
    record Period(String month) {}

    /**
     * Результат отчёта.
     *
     * @param month месяц
     * @param attempt номер попытки, на которой он получен
     */
    record Summary(String month, int attempt) {}

    /** Отчёт: результат — запись; на {@code spawn} ставит дочерний отчёт из своего потока. */
    @TaskPriority(30)
    static class ReportProcessor implements FlatTaskProcessor<Period, Summary> {

        private final TaskService tasks;

        private volatile ExecutionRef target;

        ReportProcessor(final TaskService tasks) {
            this.tasks = tasks;
        }

        @Override
        public String taskType() {
            return "report";
        }

        @Override
        public Summary process(final Task<Period> task, final ProcessingContext context) {
            if (task.payload().month().equals("spawn")) {
                this.tasks.submit("report", new Period("child"));
            } else if (task.payload().month().equals("spawn-elsewhere")) {
                this.tasks.submit("report", new Period("child"), this.target);
            }
            return new Summary(task.payload().month(), context.attemptNumber());
        }
    }

    /** Всегда падает, запоминает номер попытки и признак повтора. */
    static class FlakyProcessor implements FlatTaskProcessor<String, Void> {

        final List<Integer> attempts = new CopyOnWriteArrayList<>();

        final List<Boolean> retries = new CopyOnWriteArrayList<>();

        @Override
        public String taskType() {
            return "flaky";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            this.attempts.add(context.attemptNumber());
            this.retries.add(context.isRetry());
            throw new IllegalStateException("внешняя система недоступна");
        }
    }

    /** Запрещает повтор и падает. */
    static class BrokenProcessor implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "broken";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            context.doNotRetry("битая нагрузка");
            throw new IllegalArgumentException("нагрузка не разбирается");
        }
    }

    /** Объявляет пропуск и возвращает значение. */
    static class SkippingProcessor implements FlatTaskProcessor<String, String> {

        @Override
        public String taskType() {
            return "skipping";
        }

        @Override
        public String process(final Task<String> task, final ProcessingContext context) {
            context.skip("нечего делать");
            return "частично";
        }
    }

    /**
     * Устаревший тип: постановка отвергается.
     *
     * @deprecated тип выведен из постановки, существующие задачи доделываются
     */
    @Deprecated
    static class LegacyProcessor implements FlatTaskProcessor<String, Void> {

        @Override
        public String taskType() {
            return "legacy";
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
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
        ReportProcessor reportProcessor(final TaskService tasks) {
            return new ReportProcessor(tasks);
        }

        @Bean
        FlakyProcessor flakyProcessor() {
            return new FlakyProcessor();
        }

        @Bean
        BrokenProcessor brokenProcessor() {
            return new BrokenProcessor();
        }

        @Bean
        SkippingProcessor skippingProcessor() {
            return new SkippingProcessor();
        }

        @Bean
        @SuppressWarnings("deprecation")
        LegacyProcessor legacyProcessor() {
            return new LegacyProcessor();
        }
    }
}
