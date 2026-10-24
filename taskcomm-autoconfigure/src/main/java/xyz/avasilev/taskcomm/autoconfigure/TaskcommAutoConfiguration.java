package xyz.avasilev.taskcomm.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Корневая автоконфигурация стартера.
 *
 * <p>Подсистем здесь нет — только общий выключатель {@code taskcomm.enabled} и свойства под ним.
 * Задачи, коммуникации и REST получат собственные автоконфигурации на следующих этапах плана
 * (docs/roadmap.md). Каждая из них стоит под составным условием «{@code taskcomm.enabled}
 * и собственный выключатель подсистемы», поэтому {@code taskcomm.enabled=false} выключает всё
 * (docs/adr/0090-root-switch-disables-everything.md). Порядок
 * {@code @AutoConfiguration(after = ...)} включённость не передаёт — её передаёт только условие.
 *
 * <p>{@code @EnableConfigurationProperties} без аргументов не регистрирует ни одного бина свойств,
 * а только гарантирует связывание {@code @ConfigurationProperties} на {@code @Bean}-методе: без него
 * свойства молча остались бы значениями по умолчанию в любом контексте, где связывание не включил
 * кто-то другой.
 *
 * @see TaskcommProperties
 */
@AutoConfiguration
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
}
