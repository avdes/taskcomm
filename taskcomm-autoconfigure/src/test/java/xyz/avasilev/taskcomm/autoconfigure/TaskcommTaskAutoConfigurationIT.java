package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskRetry;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.internal.TaskEngine;
import xyz.avasilev.taskcomm.task.internal.TaskProcessorRegistry;

/**
 * Обязательные сценарии автоконфигурации задач (CLAUDE.md, раздел «Тесты») на настоящем
 * PostgreSQL: бины по умолчанию, выключение своим {@code taskcomm.task.enabled} и корневым
 * {@code taskcomm.enabled} (docs/adr/0090), по тесту на каждую точку замены — помеченный бин
 * берётся, непомеченный того же типа нет, — отсутствие бинов чужих типов (docs/adr/0096) и печать
 * итоговых настроек при старте (docs/adr/0061).
 */
@ExtendWith(OutputCaptureExtension.class)
class TaskcommTaskAutoConfigurationIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskcommAutoConfiguration.class,
                    TaskcommSchemaAutoConfiguration.class,
                    TaskcommTaskAutoConfiguration.class))
            .withUserConfiguration(Database.class, EchoConfiguration.class)
            .withBean(String.class, TestPostgres::newSchema)
            .withPropertyValues("taskcomm.schema.initialize=always", "taskcomm.task.poll.interval=100ms");

    @Test
    void createsEngineAndServiceByDefault() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TaskService.class);
            assertThat(context).hasSingleBean(TaskProcessorRegistry.class);
            assertThat(context.getBean(TaskEngine.class).isRunning()).isTrue();
        });
    }

    @Test
    void backsOffWhenDisabledByOwnSwitch() {
        this.contextRunner.withPropertyValues("taskcomm.task.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TaskService.class);
            assertThat(context).doesNotHaveBean(TaskEngine.class);
            assertThat(context).hasSingleBean(ExecutionService.class);
        });
    }

    @Test
    void backsOffWhenDisabledByRootSwitch() {
        this.contextRunner.withPropertyValues("taskcomm.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TaskService.class);
            assertThat(context).doesNotHaveBean(TaskEngine.class);
            assertThat(context).doesNotHaveBean(ExecutionService.class);
        });
    }

    @Test
    void registersNoBeansOfForeignTypesItConsumes() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(context).doesNotHaveBean(Executor.class);
            assertThat(context).doesNotHaveBean(ThreadFactory.class);
            assertThat(context).doesNotHaveBean(TaskDecorator.class);
            assertThat(context).doesNotHaveBean(JsonMapper.class);
            assertThat(context).doesNotHaveBean(JdbcClient.class);
        });
    }

    @Test
    void printsResolvedSettingsWithLevels(final CapturedOutput output) {
        this.contextRunner
                .withPropertyValues("taskcomm.task.types.echo.retry.jitter=0.5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output)
                            .contains("Итоговые настройки задач")
                            .contains("retry.max-attempts 4 (аннотация на классе)")
                            .contains("retry.jitter 0.5 (yaml, тип)")
                            .contains("lease.duration PT1M (глобальное значение)");
                });
    }

    @Test
    void usesPayloadMapperMarkedWithQualifier() {
        this.contextRunner.withUserConfiguration(QualifiedMapper.class).run(context -> {
            final UUID id = context.getBean(TaskService.class)
                    .submit("echo", new Echo("abc"))
                    .id();
            assertThat(payload(context.getBean(DataSource.class), id)).contains("echo_text");
        });
    }

    @Test
    void ignoresPayloadMapperWithoutQualifier() {
        this.contextRunner.withUserConfiguration(UnqualifiedMapper.class).run(context -> {
            final UUID id = context.getBean(TaskService.class)
                    .submit("echo", new Echo("abc"))
                    .id();
            assertThat(payload(context.getBean(DataSource.class), id)).contains("echoText");
        });
    }

    @Test
    void failsWhenTwoPayloadMappersAreMarked() {
        this.contextRunner
                .withUserConfiguration(TwoQualifiedMappers.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("@TaskcommPayloadMapper"));
    }

    @Test
    void usesWorkerThreadFactoryMarkedWithQualifier() {
        this.contextRunner.withUserConfiguration(QualifiedThreadFactory.class).run(context -> {
            final List<String> threads =
                    runEcho(context.getBean(TaskService.class), context.getBean(EchoProcessor.class));
            assertThat(threads).singleElement().asString().startsWith("consumer-worker-");
        });
    }

    @Test
    void ignoresWorkerThreadFactoryWithoutQualifier() {
        this.contextRunner.withUserConfiguration(UnqualifiedThreadFactory.class).run(context -> {
            final List<String> threads =
                    runEcho(context.getBean(TaskService.class), context.getBean(EchoProcessor.class));
            assertThat(threads).singleElement().asString().startsWith("taskcomm-task-main-");
        });
    }

    @Test
    void usesWorkerTaskDecoratorMarkedWithQualifier() {
        this.contextRunner.withUserConfiguration(QualifiedDecorator.class).run(context -> {
            runEcho(context.getBean(TaskService.class), context.getBean(EchoProcessor.class));
            assertThat(context.getBean(CountingDecorator.class).decorated.get()).isPositive();
        });
    }

    @Test
    void ignoresWorkerTaskDecoratorWithoutQualifier() {
        this.contextRunner.withUserConfiguration(UnqualifiedDecorator.class).run(context -> {
            runEcho(context.getBean(TaskService.class), context.getBean(EchoProcessor.class));
            assertThat(context.getBean(CountingDecorator.class).decorated.get()).isZero();
        });
    }

    @Test
    void runsPollersAndHeartbeatOnSchedulerMarkedWithQualifier() {
        this.contextRunner.withUserConfiguration(QualifiedScheduler.class).run(context -> {
            assertThat(context).hasNotFailed();
            // такт партиций, такт основного поллера и heartbeat
            assertThat(context.getBean(CountingScheduler.class).scheduled.get()).isEqualTo(3);
        });
    }

    @Test
    void ignoresSchedulerWithoutQualifier() {
        this.contextRunner.withUserConfiguration(UnqualifiedScheduler.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CountingScheduler.class).scheduled.get()).isZero();
        });
    }

    private static List<String> runEcho(final TaskService tasks, final EchoProcessor echo) {
        tasks.submit("echo", new Echo("run"));
        await().atMost(WAIT).until(() -> !echo.threads.isEmpty());
        return List.copyOf(echo.threads);
    }

    private static String payload(final DataSource dataSource, final UUID id) {
        return JdbcClient.create(dataSource)
                .sql("SELECT payload::text FROM tc_task WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static JsonMapper snakeCase() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }

    private static ThreadFactory consumerThreadFactory() {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, "consumer-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Нагрузка с именем поля из двух слов — по нему видно, чей маппер её записал.
     *
     * @param echoText текст
     */
    record Echo(String echoText) {}

    /** Запоминает поток, в котором выполнялся. */
    @TaskRetry(maxAttempts = 4)
    static class EchoProcessor implements FlatTaskProcessor<Echo, Void> {

        final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public String taskType() {
            return "echo";
        }

        @Override
        public Void process(final Task<Echo> task, final ProcessingContext context) {
            this.threads.add(Thread.currentThread().getName());
            return null;
        }
    }

    /** Считает обёрнутые запуски. */
    static class CountingDecorator implements TaskDecorator {

        final AtomicInteger decorated = new AtomicInteger();

        @Override
        public Runnable decorate(final Runnable runnable) {
            this.decorated.incrementAndGet();
            return runnable;
        }
    }

    /** Считает регулярные такты, поставленные на планировщик. */
    static class CountingScheduler extends ThreadPoolTaskScheduler {

        private static final long serialVersionUID = 1L;

        final transient AtomicInteger scheduled = new AtomicInteger();

        CountingScheduler() {
            this.setPoolSize(2);
            this.initialize();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                final Runnable task, final Instant start, final Duration delay) {
            this.scheduled.incrementAndGet();
            return super.scheduleWithFixedDelay(task, start, delay);
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

    /** Процессор теста. */
    @Configuration(proxyBeanMethods = false)
    static class EchoConfiguration {

        @Bean
        EchoProcessor echoProcessor() {
            return new EchoProcessor();
        }
    }

    /** Маппер со змеиным регистром под квалификатором. */
    @Configuration(proxyBeanMethods = false)
    static class QualifiedMapper {

        @Bean
        @TaskcommPayloadMapper
        JsonMapper consumerMapper() {
            return snakeCase();
        }
    }

    /** Тот же маппер без квалификатора. */
    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedMapper {

        @Bean
        JsonMapper consumerMapper() {
            return snakeCase();
        }
    }

    /** Два помеченных маппера. */
    @Configuration(proxyBeanMethods = false)
    static class TwoQualifiedMappers {

        @Bean
        @TaskcommPayloadMapper
        JsonMapper firstMapper() {
            return snakeCase();
        }

        @Bean
        @TaskcommPayloadMapper
        JsonMapper secondMapper() {
            return snakeCase();
        }
    }

    /** Фабрика потоков под квалификатором. */
    @Configuration(proxyBeanMethods = false)
    static class QualifiedThreadFactory {

        @Bean
        @TaskcommWorkerThreadFactory
        ThreadFactory consumerThreads() {
            return consumerThreadFactory();
        }
    }

    /** Та же фабрика без квалификатора. */
    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedThreadFactory {

        @Bean
        ThreadFactory consumerThreads() {
            return consumerThreadFactory();
        }
    }

    /** Декоратор под квалификатором. */
    @Configuration(proxyBeanMethods = false)
    static class QualifiedDecorator {

        @Bean
        @TaskcommWorkerTaskDecorator
        CountingDecorator consumerDecorator() {
            return new CountingDecorator();
        }
    }

    /** Тот же декоратор без квалификатора. */
    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedDecorator {

        @Bean
        CountingDecorator consumerDecorator() {
            return new CountingDecorator();
        }
    }

    /** Планировщик под квалификатором. */
    @Configuration(proxyBeanMethods = false)
    static class QualifiedScheduler {

        @Bean(destroyMethod = "shutdown")
        @TaskcommScheduler
        CountingScheduler consumerScheduler() {
            return new CountingScheduler();
        }
    }

    /** Тот же планировщик без квалификатора. */
    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedScheduler {

        @Bean(destroyMethod = "shutdown")
        CountingScheduler consumerScheduler() {
            return new CountingScheduler();
        }
    }
}
