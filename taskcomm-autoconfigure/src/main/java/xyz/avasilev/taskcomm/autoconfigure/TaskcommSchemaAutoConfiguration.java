package xyz.avasilev.taskcomm.autoconfigure;

import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenance;
import xyz.avasilev.taskcomm.schema.internal.PartitionMaintenanceScheduler;
import xyz.avasilev.taskcomm.schema.internal.PartitionSettings;
import xyz.avasilev.taskcomm.schema.internal.RangeCalendar;
import xyz.avasilev.taskcomm.schema.internal.SchemaStartup;
import xyz.avasilev.taskcomm.schema.internal.SchemaVerifier;
import xyz.avasilev.taskcomm.schema.internal.TaskcommSchemaInitializer;

/**
 * Схема БД: {@code taskcomm.schema.initialize}, проверка схемы при старте, партиции и граница
 * активных партиций (docs/adr/0107-schema-internal-package.md).
 *
 * <p>Своего выключателя у схемы нет — она общая для подсистем, — её выключает только корневой
 * {@code taskcomm.enabled} (docs/adr/0090-root-switch-disables-everything.md). DDL партиций
 * выключается {@code taskcomm.schema.partitions.manage: false}, проверка схемы остаётся.
 */
@AutoConfiguration(
        after = {
            TaskcommAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class
        })
@ConditionalOnProperty(prefix = "taskcomm", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(JdbcClient.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class TaskcommSchemaAutoConfiguration {

    /**
     * Инициализатор схемы. Создаётся всегда; при {@code initialize: never} ничего не делает.
     *
     * @param properties свойства стартера
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param beanFactory фабрика бинов
     * @return инициализатор схемы стартера
     */
    @Bean
    public TaskcommSchemaInitializer taskcommSchemaInitializer(
            final TaskcommProperties properties,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory) {
        return new TaskcommSchemaInitializer(
                TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory),
                properties.getSchema().getInitialize());
    }

    /**
     * Обслуживание партиций. Транзакции — свои, с явным {@code READ COMMITTED}
     * (docs/rules/persistence.md).
     *
     * @param properties свойства стартера
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param qualifiedManagers менеджеры транзакций под {@link TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @param beanFactory фабрика бинов
     * @return обслуживание партиций
     */
    @Bean
    public PartitionMaintenance taskcommPartitionMaintenance(
            final TaskcommProperties properties,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers,
            final ListableBeanFactory beanFactory) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        final TransactionTemplate transactions = new TransactionTemplate(
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new PartitionMaintenance(JdbcClient.create(dataSource), transactions, partitionSettings(properties));
    }

    /**
     * Проверка схемы и подготовка партиций при старте — после инициализации базы, до всего,
     * что работает с таблицами стартера.
     *
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param beanFactory фабрика бинов
     * @param maintenance обслуживание партиций
     * @return выполненная проверка
     */
    @Bean
    @DependsOn("taskcommSchemaInitializer")
    @DependsOnDatabaseInitialization
    public SchemaStartup taskcommSchemaStartup(
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory,
            final PartitionMaintenance maintenance) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new SchemaStartup(new SchemaVerifier(JdbcClient.create(dataSource)), maintenance);
    }

    /**
     * Такт обслуживания партиций — после проверки схемы при старте.
     *
     * @param properties свойства стартера
     * @param maintenance обслуживание партиций
     * @param schedulers планировщик под {@link TaskcommScheduler}, если объявлен
     * @return такт обслуживания
     */
    @Bean
    @DependsOn("taskcommSchemaStartup")
    public PartitionMaintenanceScheduler taskcommPartitionMaintenanceScheduler(
            final TaskcommProperties properties,
            final PartitionMaintenance maintenance,
            @TaskcommScheduler final ObjectProvider<TaskScheduler> schedulers) {
        return new PartitionMaintenanceScheduler(
                maintenance,
                properties.getSchema().getPartitions().getInterval(),
                TaskcommBeanSelection.scheduler(schedulers));
    }

    private static PartitionSettings partitionSettings(final TaskcommProperties properties) {
        final TaskcommProperties.Partitions partitions = properties.getSchema().getPartitions();
        return new PartitionSettings(
                partitions.isManage(),
                RangeCalendar.valueOf(partitions.getPeriod().name()),
                partitions.getCreateAhead(),
                partitions.getInterval(),
                partitions.getDrop().isEnabled(),
                partitions.getDrop().getRetention());
    }
}
