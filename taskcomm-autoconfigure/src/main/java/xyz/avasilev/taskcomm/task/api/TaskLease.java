package xyz.avasilev.taskcomm.task.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;

/**
 * Сокращение {@link TaskProcessorSettings} для аренды и предела обработки
 * (docs/adr/0113-task-processor-settings-attributes.md). На классе и на методе.
 *
 * <pre>{@code
 * @Override
 * @TaskLease(maxExecutionTime = "2h")
 * public void expandBatch(TaskBatch<SupplierBatch> batch, Emitter<ItemPrice> units, ProcessingContext context) {
 *     ...
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TaskProcessorSettings
public @interface TaskLease {

    /**
     * Длительность аренды.
     *
     * @return длительность; пусто — не задана
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "leaseDuration")
    String duration() default "";

    /**
     * Интервал heartbeat.
     *
     * @return интервал; пусто — не задан
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "heartbeatInterval")
    String heartbeatInterval() default "";

    /**
     * Предел обработки одной попытки.
     *
     * @return предел; пусто — не задан
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "maxExecutionTime")
    String maxExecutionTime() default "";
}
