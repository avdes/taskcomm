package xyz.avasilev.taskcomm.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Корневая автоконфигурация стартера.
 *
 * <p>Подсистем здесь нет — только общий выключатель {@code taskcomm.enabled} и свойства под ним.
 * Задачи, коммуникации и REST получат собственные автоконфигурации с собственными выключателями
 * на следующих этапах плана (docs/roadmap.md); от корневой они не наследуются, а лишь идут после
 * неё по порядку через {@code @AutoConfiguration(after = ...)}.
 *
 * @see TaskcommProperties
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "taskcomm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TaskcommAutoConfiguration {

    /**
     * Регистрирует {@link TaskcommProperties}, если потребитель не определил собственный бин
     * такого типа.
     *
     * @return свойства стартера, связанные со значениями из {@code taskcomm.*}
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "taskcomm")
    public TaskcommProperties taskcommProperties() {
        return new TaskcommProperties();
    }
}
