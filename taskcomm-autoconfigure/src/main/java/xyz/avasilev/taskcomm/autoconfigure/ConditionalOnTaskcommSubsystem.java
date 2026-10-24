package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Составное условие автоконфигурации подсистемы: включены и корневой {@code taskcomm.enabled},
 * и выключатель подсистемы {@code taskcomm.<подсистема>.enabled}; оба по умолчанию {@code true}
 * (docs/adr/0090-root-switch-disables-everything.md). Порядок {@code @AutoConfiguration(after = ...)}
 * включённость не передаёт — без этого условия {@code taskcomm.enabled: false} выключил бы только
 * корень, а поллеры и heartbeat продолжили бы работать.
 *
 * <p>Не публичный API: аннотация видна только в пакете автоконфигураций.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnTaskcommSubsystem.OnSubsystem.class)
@interface ConditionalOnTaskcommSubsystem {

    /**
     * Имя подсистемы — середина имени выключателя: {@code task}, {@code communication}, {@code rest}.
     *
     * @return имя подсистемы
     */
    String value();

    /** Проверка обоих выключателей. */
    final class OnSubsystem extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
            final Map<String, Object> attributes =
                    metadata.getAnnotationAttributes(ConditionalOnTaskcommSubsystem.class.getName());
            final String subsystem = String.valueOf(attributes == null ? null : attributes.get("value"));
            final PropertyResolver properties = context.getEnvironment();
            if (!properties.getProperty("taskcomm.enabled", Boolean.class, true)) {
                return ConditionOutcome.noMatch("taskcomm.enabled = false выключает все подсистемы");
            }
            final String own = "taskcomm." + subsystem + ".enabled";
            if (!properties.getProperty(own, Boolean.class, true)) {
                return ConditionOutcome.noMatch(own + " = false");
            }
            return ConditionOutcome.match("taskcomm.enabled и " + own + " включены");
        }
    }
}
