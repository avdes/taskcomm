package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Точка замены планировщика стартера: помеченный бин {@code TaskScheduler} потребителя
 * заменяет собственный планировщик стартера (docs/adr/0096-no-foreign-type-beans.md).
 *
 * <p>На планировщике идут такты обслуживания партиций, а с движками — такты поллеров и heartbeat.
 * Занятый планировщик задерживает продление аренды, поэтому, если по нему видно число потоков
 * и оно меньше двух, стартер громко предупреждает при старте. Бин {@code TaskScheduler} без этой
 * аннотации стартер не берёт — иначе он забрал бы планировщик {@code @Scheduled} потребителя.
 * Помеченных бинов больше одного — падение при старте.
 *
 * <p>Остановку помеченного планировщика стартер не выполняет: бин принадлежит потребителю.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface TaskcommScheduler {}
