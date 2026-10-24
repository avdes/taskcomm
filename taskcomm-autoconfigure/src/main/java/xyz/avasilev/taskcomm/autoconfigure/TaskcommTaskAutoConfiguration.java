package xyz.avasilev.taskcomm.autoconfigure;

import java.util.concurrent.ThreadFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.internal.DefaultTaskService;
import xyz.avasilev.taskcomm.task.internal.PollerLayout;
import xyz.avasilev.taskcomm.task.internal.TaskCreator;
import xyz.avasilev.taskcomm.task.internal.TaskEngine;
import xyz.avasilev.taskcomm.task.internal.TaskPayloadCodec;
import xyz.avasilev.taskcomm.task.internal.TaskProcessorRegistry;
import xyz.avasilev.taskcomm.task.internal.TaskSettings;
import xyz.avasilev.taskcomm.task.internal.TaskStartup;

/**
 * Подсистема задач: реестр процессоров, итоговые настройки, {@link TaskService} и движок —
 * поллеры с пулами воркеров и поток heartbeat.
 *
 * <p>Стоит под составным условием «{@code taskcomm.enabled} и {@code taskcomm.task.enabled}»
 * (docs/adr/0090-root-switch-disables-everything.md). Бины внутренних типов и {@link TaskService}
 * объявлены без {@code @ConditionalOnMissingBean}: точки замены — только бины потребителя
 * с квалификаторами стартера (docs/adr/0096-no-foreign-type-beans.md). Маппер, планировщик и пулы —
 * поля внутренних бинов, бинами чужих типов они не регистрируются.
 *
 * <p>Движок стартует после проверки схемы при старте.
 */
@AutoConfiguration(
        after = {
            TaskcommAutoConfiguration.class,
            TaskcommSchemaAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class
        })
@ConditionalOnTaskcommSubsystem("task")
@ConditionalOnClass(JdbcClient.class)
public class TaskcommTaskAutoConfiguration {

    /**
     * Реестр процессоров из всех бинов {@link TaskProcessor} потребителя.
     *
     * @param processors процессоры
     * @return реестр
     */
    @Bean
    public TaskProcessorRegistry taskcommTaskProcessorRegistry(final ObjectProvider<TaskProcessor<?>> processors) {
        return new TaskProcessorRegistry(processors.orderedStream().toList());
    }

    /**
     * Раскладка поллеров: основной и выделенные.
     *
     * @param properties свойства стартера
     * @return раскладка
     */
    @Bean
    public PollerLayout taskcommTaskPollerLayout(final TaskcommProperties properties) {
        return TaskPropertiesMapping.pollers(properties.getTask());
    }

    /**
     * Итоговые настройки по пяти уровням; при сборке проверяются и печатаются.
     *
     * @param properties свойства стартера
     * @param registry реестр процессоров
     * @param pollers раскладка поллеров
     * @return итоговые настройки
     */
    @Bean
    public TaskSettings taskcommTaskSettings(
            final TaskcommProperties properties, final TaskProcessorRegistry registry, final PollerLayout pollers) {
        return TaskSettings.resolve(
                TaskPropertiesMapping.global(properties.getTask()),
                TaskPropertiesMapping.types(properties.getTask()),
                registry,
                pollers,
                TaskPropertiesMapping.discard(properties.getResults()),
                TaskPropertiesMapping.safety(properties.getTask().getSafety()));
    }

    /**
     * Кодек нагрузок и результатов: маппер потребителя под {@link TaskcommPayloadMapper} или свой.
     *
     * @param mappers бины {@code JsonMapper} под {@link TaskcommPayloadMapper}
     * @return кодек
     */
    @Bean
    public TaskPayloadCodec taskcommTaskPayloadCodec(@TaskcommPayloadMapper final ObjectProvider<JsonMapper> mappers) {
        final JsonMapper consumer =
                TaskcommBeanSelection.replacement(mappers, JsonMapper.class, "@TaskcommPayloadMapper");
        return new TaskPayloadCodec(
                consumer != null ? consumer : JsonMapper.builder().build());
    }

    /**
     * Создание задачи в транзакции потребителя или в своей
     * (docs/adr/0094-creation-always-in-transaction.md).
     *
     * @param properties свойства стартера
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param qualifiedManagers менеджеры транзакций под {@link TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @param beanFactory фабрика бинов
     * @param executions хранилище дел
     * @param ids генератор идентификаторов
     * @return создание задачи
     */
    @Bean
    public TaskCreator taskcommTaskCreator(
            final TaskcommProperties properties,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers,
            final ListableBeanFactory beanFactory,
            final ExecutionRepository executions,
            final UuidV7 ids) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new TaskCreator(
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource),
                executions,
                ids,
                TaskPropertiesMapping.range(
                        properties.getSchema().getPartitions().getPeriod()));
    }

    /**
     * Постановка и чтение задач. Точкой замены не является. Реестр и настройки сервис берёт при
     * первом вызове: процессоры с внедрённым {@link TaskService} не замыкают реестр на сервис.
     *
     * @param registry реестр процессоров
     * @param settings итоговые настройки
     * @param creator создание задачи
     * @param codec кодек
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param beanFactory фабрика бинов
     * @param qualifiedManagers менеджеры транзакций под {@link TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @return сервис задач
     */
    @Bean
    public TaskService taskcommTaskService(
            final ObjectProvider<TaskProcessorRegistry> registry,
            final ObjectProvider<TaskSettings> settings,
            final TaskCreator creator,
            final TaskPayloadCodec codec,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new DefaultTaskService(
                registry::getObject,
                settings::getObject,
                creator,
                codec,
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource));
    }

    /**
     * Подготовка задач при старте — после проверки схемы: синхронизация белого списка полей поиска
     * и предупреждение об осиротевших задачах.
     *
     * @param properties свойства стартера
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param qualifiedManagers менеджеры транзакций под {@link TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @param beanFactory фабрика бинов
     * @param ids генератор идентификаторов
     * @param registry реестр процессоров
     * @return подготовка
     */
    @Bean
    @DependsOn("taskcommSchemaStartup")
    public TaskStartup taskcommTaskStartup(
            final TaskcommProperties properties,
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers,
            final ListableBeanFactory beanFactory,
            final UuidV7 ids,
            final TaskProcessorRegistry registry) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new TaskStartup(
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource),
                ids,
                registry,
                properties.getSearch().isRemoveUndeclaredFields());
    }

    /**
     * Движок задач — после проверки схемы при старте.
     *
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param qualifiedManagers менеджеры транзакций под {@link TaskcommTransactionManager}
     * @param managers все менеджеры транзакций
     * @param beanFactory фабрика бинов
     * @param ids генератор идентификаторов
     * @param settings итоговые настройки с раскладкой поллеров
     * @param registry реестр процессоров
     * @param codec кодек нагрузок и результатов
     * @param threadFactories бины {@code ThreadFactory} под {@link TaskcommWorkerThreadFactory}
     * @param decorators бины {@code TaskDecorator} под {@link TaskcommWorkerTaskDecorator}
     * @param schedulers бины {@code TaskScheduler} под {@link TaskcommScheduler}
     * @return движок
     */
    @Bean
    @DependsOn({"taskcommSchemaStartup", "taskcommTaskStartup"})
    public TaskEngine taskcommTaskEngine(
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            @TaskcommTransactionManager final ObjectProvider<PlatformTransactionManager> qualifiedManagers,
            final ObjectProvider<PlatformTransactionManager> managers,
            final ListableBeanFactory beanFactory,
            final UuidV7 ids,
            final TaskSettings settings,
            final TaskProcessorRegistry registry,
            final TaskPayloadCodec codec,
            @TaskcommWorkerThreadFactory final ObjectProvider<ThreadFactory> threadFactories,
            @TaskcommWorkerTaskDecorator final ObjectProvider<TaskDecorator> decorators,
            @TaskcommScheduler final ObjectProvider<TaskScheduler> schedulers) {
        final DataSource dataSource = TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory);
        return new TaskEngine(
                dataSource,
                TaskcommBeanSelection.transactionManager(qualifiedManagers, managers, beanFactory, dataSource),
                ids,
                settings,
                registry,
                codec,
                TaskcommBeanSelection.replacement(threadFactories, ThreadFactory.class, "@TaskcommWorkerThreadFactory"),
                TaskcommBeanSelection.replacement(decorators, TaskDecorator.class, "@TaskcommWorkerTaskDecorator"),
                TaskcommBeanSelection.scheduler(schedulers));
    }
}
