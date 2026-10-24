package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;

/**
 * Обязательные сценарии для автоконфигурации (CLAUDE.md, раздел «Тесты»): бины создаются
 * по умолчанию, конфигурация выключается свойством, бинов чужих типов в контексте не появляется.
 * У корневой автоконфигурации свой выключатель и корневой {@code taskcomm.enabled} совпадают,
 * поэтому сценарии «своим {@code enabled=false}» и «корнем» здесь один тест; у автоконфигураций
 * подсистем они разные (docs/adr/0090-root-switch-disables-everything.md). Точек замены у корневой
 * автоконфигурации нет, и тестов на них здесь нет (docs/adr/0096-no-foreign-type-beans.md).
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
    void bindsPropertiesWithoutHelpFromOtherAutoConfigurations() {
        this.contextRunner
                .withPropertyValues(
                        "taskcomm.schema.initialize=always",
                        "taskcomm.schema.partitions.period=week",
                        "taskcomm.schema.partitions.drop.retention=30d")
                .run(context -> {
                    final TaskcommProperties.Schema schema =
                            context.getBean(TaskcommProperties.class).getSchema();
                    assertThat(schema.getInitialize()).isEqualTo(DatabaseInitializationMode.ALWAYS);
                    assertThat(schema.getPartitions().getPeriod()).isEqualTo(PartitionPeriod.WEEK);
                    assertThat(schema.getPartitions().getDrop().getRetention()).isEqualTo(Duration.ofDays(30));
                });
    }

    @Test
    void backsOffWhenDisabledByProperty() {
        this.contextRunner
                .withPropertyValues("taskcomm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TaskcommProperties.class));
    }

    @Test
    void registersNoBeansOfForeignTypesItConsumes() {
        this.contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(Executor.class);
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(context).doesNotHaveBean(ThreadFactory.class);
            assertThat(context).doesNotHaveBean(TaskDecorator.class);

            final List<String> jacksonBeans = Arrays.stream(context.getBeanDefinitionNames())
                    .filter(name -> isJacksonType(context.getType(name)))
                    .toList();
            assertThat(jacksonBeans).isEmpty();
        });
    }

    /**
     * Сравнивает по имени пакета, чтобы тест не зависел от наличия Jackson в classpath.
     *
     * @param type тип бина или {@code null}, если его не удалось определить
     * @return {@code true}, если тип принадлежит Jackson 3 или Jackson 2
     */
    private static boolean isJacksonType(final Class<?> type) {
        return type != null
                && (type.getName().startsWith("tools.jackson.")
                        || type.getName().startsWith("com.fasterxml.jackson."));
    }
}
