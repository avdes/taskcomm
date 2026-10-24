package xyz.avasilev.taskcomm.task.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;

/**
 * Сокращение {@link TaskProcessorSettings} для приоритета по умолчанию
 * (docs/adr/0113-task-processor-settings-attributes.md). Только на классе процессора — это ловит
 * компилятор.
 *
 * <pre>{@code
 * @TaskPriority(10)
 * public class UrgentOrderProcessor implements FlatTaskProcessor<OrderRef, Void> { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TaskProcessorSettings
public @interface TaskPriority {

    /**
     * Приоритет задач типа по умолчанию. Шкала по возрастанию: <b>меньше значит важнее</b>,
     * единица важнее сотни.
     *
     * @return приоритет, больше нуля
     */
    @AliasFor(annotation = TaskProcessorSettings.class, attribute = "priority")
    int value();
}
