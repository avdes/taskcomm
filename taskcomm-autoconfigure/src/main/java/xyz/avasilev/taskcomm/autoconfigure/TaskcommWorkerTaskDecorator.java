package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Точка замены обёртки запуска работы воркером: помеченный бин
 * {@code TaskDecorator} потребителя оборачивает каждый запуск работы в пулах воркеров стартера (docs/adr/0096-no-foreign-type-beans.md).
 *
 * <p>Обычное применение — перенос контекста потребителя (MDC, контекст безопасности) в поток
 * воркера. Бин {@code TaskDecorator} без этой аннотации стартер не берёт — иначе он забрал бы
 * декоратор {@code @Async} потребителя. Помеченных бинов больше одного — падение при старте.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface TaskcommWorkerTaskDecorator {}
