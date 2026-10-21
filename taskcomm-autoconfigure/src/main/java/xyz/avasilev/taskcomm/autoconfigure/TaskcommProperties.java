package xyz.avasilev.taskcomm.autoconfigure;

import lombok.Getter;
import lombok.Setter;

/**
 * Свойства стартера с префиксом {@code taskcomm}.
 *
 * <p>Класс мутабельный, а не {@code record}, хотя для value-объектов записи предпочтительны
 * (см. CLAUDE.md, раздел «Стиль кода»): тип регистрируется явным {@code @Bean}-методом
 * с {@code @ConditionalOnMissingBean} (правило стартера — каждый бин объявляется явным методом),
 * а конструкторное связывание записи проходит только через {@code @EnableConfigurationProperties},
 * минуя этот метод и его условие. Мутабельный JavaBean с обычным {@code @Bean}-методом сохраняет
 * и явное объявление, и условие.
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
