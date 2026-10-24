package xyz.avasilev.taskcomm.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Точка замены маппера нагрузки: помеченный бин {@code JsonMapper} (Jackson 3) потребителя
 * заменяет собственный маппер стартера для нагрузок и результатов задач (docs/adr/0096-no-foreign-type-beans.md).
 *
 * <p><b>Помеченный маппер определяет формат хранимых данных.</b> Сменить его конфигурацию у работающей
 * системы — то же, что переименовать поле в классе нагрузки: прежние строки перестанут читаться
 * (docs/consumer-contract.md). Бин {@code JsonMapper} без этой аннотации стартер не берёт — формат
 * нагрузки не должен зависеть от настроек веб-слоя. Помеченных бинов больше одного — падение
 * при старте.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface TaskcommPayloadMapper {}
