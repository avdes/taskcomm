package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Выбирает {@code DataSource}, с которым работает стартер, когда в приложении их несколько.
 *
 * <p>Это квалификатор выбора, а не точка замены: своего {@code DataSource} у стартера нет.
 * Выбранный — единственный для всего стартера: запись, захват, heartbeat, партиции, проверка
 * схемы и {@code taskcomm.schema.initialize}. Нет помеченного — берётся единственный
 * или {@code @Primary}; иначе приложение не стартует и называет кандидатов. Помеченных больше
 * одного — тоже падение при старте (docs/adr/0094-creation-always-in-transaction.md).
 *
 * <pre>{@code
 * @Bean
 * @TaskcommDataSource
 * DataSource taskcommDataSource() { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface TaskcommDataSource {}
