package xyz.avasilev.taskcomm.task.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;

/**
 * Сокращение {@link TaskProcessorSettings} для повторов и backoff
 * (docs/adr/0113-task-processor-settings-attributes.md). На классе и на методе.
 *
 * <pre>{@code
 * @TaskRetry(maxAttempts = 8, maxDelay = "4h")
 * public class SupplierSyncProcessor implements UnitTaskProcessor<...> { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TaskProcessorSettings
public @interface TaskRetry {

    /**
     * Лимит попыток в цикле повторов.
     *
     * @return лимит; {@code 0} — не задан
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "maxAttempts")
    int maxAttempts() default 0;

    /**
     * Первая задержка backoff.
     *
     * @return задержка; пусто — не задана
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "initialDelay")
    String initialDelay() default "";

    /**
     * Множитель backoff.
     *
     * @return множитель; {@code 0} — не задан
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "multiplier")
    double multiplier() default 0;

    /**
     * Верхняя граница задержки backoff.
     *
     * @return граница; пусто — не задана
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "maxDelay")
    String maxDelay() default "";

    /**
     * Доля джиттера в {@code (0, 1]}.
     *
     * @return доля; {@code 0} — не задана
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "jitter")
    double jitter() default 0;
}
