package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;
import xyz.avasilev.taskcomm.execution.internal.DefaultExecutionService;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Корневая автоконфигурация стартера: общий выключатель {@code taskcomm.enabled}, свойства и то,
 * что общее для подсистем, — дело и генератор идентификаторов.
 *
 * <p>Подсистемы — задачи, коммуникации, REST — получают собственные автоконфигурации. Каждая
 * из них стоит под составным условием «{@code taskcomm.enabled} и собственный выключатель
 * подсистемы», поэтому {@code taskcomm.enabled=false} выключает всё
 * (docs/adr/0090-root-switch-disables-everything.md). Порядок
 * {@code @AutoConfiguration(after = ...)} включённость не передаёт — её передаёт только условие.
 *
 * <p>{@link ExecutionService} живёт здесь, а не в подсистеме: дело общее для задач
 * и коммуникаций, и выключение одной подсистемы не должно отнимать его у другой.
 *
 * <p>{@code @EnableConfigurationProperties} без аргументов не регистрирует ни одного бина свойств,
 * а только гарантирует связывание {@code @ConfigurationProperties} на {@code @Bean}-методе: без него
 * свойства молча остались бы значениями по умолчанию в любом контексте, где связывание не включил
 * кто-то другой.
 *
 * @see TaskcommProperties
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "taskcomm", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties
public class TaskcommAutoConfiguration {

    /**
     * Регистрирует {@link TaskcommProperties}. Бин свойств не точка замены и потому объявляется
     * без {@code @ConditionalOnMissingBean} (docs/adr/0096-no-foreign-type-beans.md).
     *
     * @return свойства стартера, связанные со значениями из {@code taskcomm.*}
     */
    @Bean
    @ConfigurationProperties(prefix = "taskcomm")
    public TaskcommProperties taskcommProperties() {
        return new TaskcommProperties();
    }

    /**
     * Генератор UUIDv7 — один на экземпляр, общий для подсистем
     * (docs/adr/0114-uuidv7-generator-in-execution-internal.md).
     *
     * @return генератор
     */
    @Bean
    public UuidV7 taskcommUuidV7() {
        return new UuidV7(Clock.systemUTC());
    }

    /**
     * Хранилище дел. Бин внутреннего типа — без {@code @ConditionalOnMissingBean}
     * (docs/adr/0089-no-storage-spi-explicit-extension-points.md).
     *
     * @param qualified {@code DataSource} под {@link TaskcommDataSource}
     * @param dataSources все бины {@code DataSource}
     * @param beanFactory фабрика бинов
     * @param ids генератор идентификаторов
     * @return хранилище дел
     */
    @Bean
    public ExecutionRepository taskcommExecutionRepository(
            @TaskcommDataSource final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> dataSources,
            final ListableBeanFactory beanFactory,
            final UuidV7 ids) {
        return new ExecutionRepository(
                JdbcClient.create(TaskcommBeanSelection.dataSource(qualified, dataSources, beanFactory)), ids);
    }

    /**
     * Поиск дела по идентификатору. Точкой замены не является
     * (docs/adr/0096-no-foreign-type-beans.md).
     *
     * @param executions хранилище дел
     * @return сервис дел
     */
    @Bean
    public ExecutionService taskcommExecutionService(final ExecutionRepository executions) {
        return new DefaultExecutionService(executions);
    }
}
