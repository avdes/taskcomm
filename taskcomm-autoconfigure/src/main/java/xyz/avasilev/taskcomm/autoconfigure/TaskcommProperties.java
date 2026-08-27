package xyz.avasilev.taskcomm.autoconfigure;

import lombok.Getter;
import lombok.Setter;

/**
 * Свойства стартера с префиксом {@code taskcomm}.
 *
 * <p>Класс мутабельный, а не {@code record}, хотя для value-объектов записи предпочтительны
 * (см. CLAUDE.md, раздел «Стиль кода»): тип регистрируется явным {@code @Bean}-методом под
 * {@code @ConditionalOnMissingBean} (обязательное правило стартера — потребитель должен иметь
 * возможность заменить любой наш бин своим), а конструкторное связывание записи проходит только
 * через {@code @EnableConfigurationProperties}, минуя ту самую точку, где мог бы вмешаться бин
 * потребителя. Мутабельный JavaBean с обычным {@code @Bean}-методом сохраняет обе гарантии сразу.
 */
@Getter
@Setter
public class TaskcommProperties {

    /**
     * Общий выключатель стартера. По умолчанию включён: подключение зависимости не должно
     * требовать дополнительных действий, чтобы получить рабочую функциональность.
     */
    private boolean enabled = true;
}
