package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Три обязательных сценария для автоконфигурации (CLAUDE.md, раздел «Тесты»): бины создаются
 * по умолчанию, подсистема выключается своим {@code enabled=false}, бин потребителя перекрывает
 * наш — то есть {@code @ConditionalOnMissingBean} действительно работает.
 */
class TaskcommAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TaskcommAutoConfiguration.class));

    @Test
    void createsPropertiesBeanByDefault() {
        this.contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TaskcommProperties.class);
            assertThat(context.getBean(TaskcommProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void backsOffWhenDisabledByProperty() {
        this.contextRunner
                .withPropertyValues("taskcomm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TaskcommProperties.class));
    }

    @Test
    void backsOffWhenConsumerProvidesOwnBean() {
        this.contextRunner
                .withUserConfiguration(CustomPropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskcommProperties.class);
                    assertThat(context.getBean(TaskcommProperties.class))
                            .isSameAs(CustomPropertiesConfiguration.CUSTOM_PROPERTIES);
                });
    }

    /**
     * Конфигурация потребителя, объявляющая собственный бин {@link TaskcommProperties} —
     * имитирует переопределение бина стартера в коде потребителя.
     */
    @Configuration
    static class CustomPropertiesConfiguration {

        /**
         * Экземпляр, который должен пережить автоконфигурацию стартера без изменений.
         */
        static final TaskcommProperties CUSTOM_PROPERTIES = new TaskcommProperties();

        /**
         * Имитирует бин потребителя, который обязан перекрыть бин стартера.
         *
         * @return фиксированный экземпляр {@link #CUSTOM_PROPERTIES}
         */
        @Bean
        TaskcommProperties taskcommProperties() {
            return CUSTOM_PROPERTIES;
        }
    }
}
