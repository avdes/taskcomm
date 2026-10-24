package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenance;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenanceScheduler;
import xyz.avasilev.taskcomm.schema.internal.SchemaStartup;

/**
 * Обязательные сценарии автоконфигурации схемы (CLAUDE.md, раздел «Тесты») на настоящем
 * PostgreSQL: бины по умолчанию, выключение корневым {@code taskcomm.enabled} (своего выключателя
 * у схемы нет, docs/adr/0107), точка замены {@code @TaskcommScheduler}, отсутствие бинов чужих
 * типов, выбор {@code DataSource} и менеджера транзакций (docs/adr/0094, docs/adr/0096).
 */
class TaskcommSchemaAutoConfigurationIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(TaskcommAutoConfiguration.class, TaskcommSchemaAutoConfiguration.class));

    @Test
    void initializesAndVerifiesSchemaAndCreatesPartitionsByDefault() {
        final String schema = TestPostgres.newSchema();
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class)
                .withBean(String.class, () -> schema)
                .withPropertyValues("taskcomm.schema.initialize=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SchemaStartup.class);
                    assertThat(context).hasSingleBean(PartitionMaintenance.class);
                    assertThat(context).hasSingleBean(PartitionMaintenanceScheduler.class);
                    assertThat(context.getBean(PartitionMaintenanceScheduler.class)
                                    .isRunning())
                            .isTrue();
                    assertThat(partitionCount(schema)).isEqualTo(9 * 4);
                });
    }

    @Test
    void verifiesSchemaAfterConsumerDatabaseInitialization() {
        final String schema = TestPostgres.newSchema();
        this.contextRunner
                .withConfiguration(AutoConfigurations.of(DataSourceInitializationAutoConfiguration.class))
                .withUserConfiguration(SingleDataSource.class)
                .withBean(String.class, () -> schema)
                .withPropertyValues(
                        "spring.sql.init.mode=always",
                        "spring.sql.init.schema-locations=classpath:db/changelog/taskcomm/taskcomm-2.0.0.sql")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SchemaStartup.class);
                });
    }

    @Test
    void failsWithMigrationHintWhenSchemaIsMissing() {
        final String schema = TestPostgres.newSchema();
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class)
                .withBean(String.class, () -> schema)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("Примените миграции taskcomm"));
    }

    @Test
    void backsOffWhenDisabledByRootSwitch() {
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class)
                .withBean(String.class, TestPostgres::newSchema)
                .withPropertyValues("taskcomm.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SchemaStartup.class);
                    assertThat(context).doesNotHaveBean(PartitionMaintenanceScheduler.class);
                });
    }

    @Test
    void registersNoBeansOfForeignTypesItConsumes() {
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class)
                .withBean(String.class, TestPostgres::newSchema)
                .withPropertyValues("taskcomm.schema.initialize=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TaskScheduler.class);
                    assertThat(context).doesNotHaveBean(Executor.class);
                    assertThat(context).doesNotHaveBean(ThreadFactory.class);
                    assertThat(context).doesNotHaveBean(TaskDecorator.class);
                    assertThat(context).doesNotHaveBean(JdbcClient.class);
                    assertThat(context).getBeans(DataSource.class).hasSize(1);
                    assertThat(context)
                            .getBeans(PlatformTransactionManager.class)
                            .hasSize(1);
                });
    }

    @Test
    void usesConsumerSchedulerMarkedWithQualifier() {
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class, QualifiedScheduler.class)
                .withBean(String.class, TestPostgres::newSchema)
                .withPropertyValues("taskcomm.schema.initialize=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CountingScheduler.class)
                                    .scheduled
                                    .get())
                            .isEqualTo(1);
                });
    }

    @Test
    void ignoresConsumerSchedulerWithoutQualifier() {
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class, UnqualifiedScheduler.class)
                .withBean(String.class, TestPostgres::newSchema)
                .withPropertyValues("taskcomm.schema.initialize=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CountingScheduler.class)
                                    .scheduled
                                    .get())
                            .isZero();
                });
    }

    @Test
    void failsWhenTwoSchedulersAreMarked() {
        this.contextRunner
                .withUserConfiguration(SingleDataSource.class, TwoQualifiedSchedulers.class)
                .withBean(String.class, TestPostgres::newSchema)
                .withPropertyValues("taskcomm.schema.initialize=always")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("@TaskcommScheduler"));
    }

    @Test
    void usesDataSourceMarkedWithQualifier() {
        final String marked = TestPostgres.newSchema();
        final String other = TestPostgres.newSchema();
        this.contextRunner
                .withUserConfiguration(MarkedDataSource.class)
                .withBean(Schemas.class, () -> new Schemas(marked, other))
                .withPropertyValues("taskcomm.schema.initialize=always")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableCount(marked)).isPositive();
                    assertThat(tableCount(other)).isZero();
                });
    }

    @Test
    void failsWhenDataSourceIsAmbiguous() {
        this.contextRunner
                .withBean("first", DataSource.class, () -> TestPostgres.dataSource("public"))
                .withBean("second", DataSource.class, () -> TestPostgres.dataSource("public"))
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("[first, second]")
                        .hasMessageContaining("@TaskcommDataSource или @Primary"));
    }

    @Test
    void failsWhenTransactionManagerUsesAnotherDataSource() {
        final String schema = TestPostgres.newSchema();
        this.contextRunner
                .withBean(DataSource.class, () -> TestPostgres.dataSource(schema))
                .withBean(
                        PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(TestPostgres.dataSource(schema)))
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(TaskcommConfigurationException.class)
                        .hasMessageContaining("работает не с тем DataSource"));
    }

    private static int partitionCount(final String schema) {
        return JdbcClient.create(TestPostgres.dataSource(schema))
                .sql("SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                        + " WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind = 'r'")
                .query(Integer.class)
                .single();
    }

    private static int tableCount(final String schema) {
        return JdbcClient.create(TestPostgres.dataSource(schema))
                .sql("SELECT count(*) FROM pg_tables WHERE schemaname = current_schema()")
                .query(Integer.class)
                .single();
    }

    /** {@code DataSource} на схему из бина {@code String} и менеджер транзакций на нём. */
    @Configuration(proxyBeanMethods = false)
    static class SingleDataSource {

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

    /**
     * Схемы для двух {@code DataSource}.
     *
     * @param marked схема помеченного
     * @param other схема второго
     */
    record Schemas(String marked, String other) {}

    /** Два {@code DataSource}, один под квалификатором стартера, менеджер транзакций — на нём. */
    @Configuration(proxyBeanMethods = false)
    static class MarkedDataSource {

        @Bean
        @TaskcommDataSource
        DataSource marked(final Schemas schemas) {
            return TestPostgres.dataSource(schemas.marked());
        }

        @Bean
        DataSource other(final Schemas schemas) {
            return TestPostgres.dataSource(schemas.other());
        }

        @Bean
        DataSourceTransactionManager transactionManager(@TaskcommDataSource final DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    /** Планировщик, считающий поставленные такты. */
    static class CountingScheduler extends ThreadPoolTaskScheduler {

        final AtomicInteger scheduled = new AtomicInteger();

        CountingScheduler() {
            this.setPoolSize(2);
            this.initialize();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                final Runnable task, final Instant startTime, final Duration delay) {
            this.scheduled.incrementAndGet();
            return super.scheduleWithFixedDelay(task, startTime, delay);
        }
    }

    /** Планировщик потребителя под квалификатором стартера. */
    @Configuration(proxyBeanMethods = false)
    static class QualifiedScheduler {

        @Bean
        @TaskcommScheduler
        CountingScheduler consumerScheduler() {
            return new CountingScheduler();
        }
    }

    /** Планировщик потребителя без квалификатора — например, для его {@code @Scheduled}. */
    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedScheduler {

        @Bean
        CountingScheduler consumerScheduler() {
            return new CountingScheduler();
        }
    }

    /** Два планировщика под квалификатором стартера. */
    @Configuration(proxyBeanMethods = false)
    static class TwoQualifiedSchedulers {

        @Bean
        @TaskcommScheduler
        CountingScheduler firstScheduler() {
            return new CountingScheduler();
        }

        @Bean
        @TaskcommScheduler
        CountingScheduler secondScheduler() {
            return new CountingScheduler();
        }
    }
}
