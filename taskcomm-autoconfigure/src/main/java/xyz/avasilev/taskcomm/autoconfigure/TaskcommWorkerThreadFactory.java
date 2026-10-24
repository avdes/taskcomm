package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Точка замены фабрики потоков воркеров: помеченный бин {@code ThreadFactory}
 * потребителя создаёт потоки во всех пулах воркеров стартера (docs/adr/0096-no-foreign-type-beans.md).
 *
 * <p>Пулы создаёт только стартер, по одному на поллер, размером {@code workers}; готовый пул
 * подставить нельзя — виртуальные потоки и свои имена потоков доступны только через фабрику.
 * Бин {@code ThreadFactory} без этой аннотации стартер не берёт. Помеченных бинов больше одного —
 * падение при старте.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface TaskcommWorkerThreadFactory {}
