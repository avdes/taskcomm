package xyz.avasilev.taskcomm.autoconfigure;

import lombok.Getter;
import lombok.Setter;

/**
 * Свойства стартера с префиксом {@code taskcomm}.
 *
 * <p>Класс мутабельный, а не {@code record}, хотя для value-объектов записи предпочтительны
 * (см. CLAUDE.md, раздел «Стиль кода»): по правилу стартера каждый бин объявляется явным
 * {@code @Bean}-методом, а конструкторное связывание записи проходит только через
 * {@code @EnableConfigurationProperties}, минуя такой метод. Точкой замены бин свойств
 * не является (docs/adr/0096-no-foreign-type-beans.md).
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
