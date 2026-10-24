package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Выбирает менеджер транзакций, которым стартер открывает свои транзакции, когда в приложении
 * их несколько.
 *
 * <p>Это квалификатор выбора, а не точка замены. Нет помеченного — берётся единственный
 * или {@code @Primary}; иначе приложение не стартует и называет кандидатов. Если у выбранного
 * менеджера виден его {@code DataSource} и это не {@code DataSource} стартера
 * (см. {@link TaskcommDataSource}) — тоже падение при старте; если не виден (JTA) — громкое
 * предупреждение (docs/adr/0094-creation-always-in-transaction.md).
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface TaskcommTransactionManager {}
