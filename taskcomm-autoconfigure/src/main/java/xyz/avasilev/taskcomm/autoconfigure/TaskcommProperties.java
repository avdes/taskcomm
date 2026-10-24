package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

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
     * Выключенный стартер не делает ничего — ни проверки схемы, ни партиций, ни поллеров.
     */
    private boolean enabled = true;

    /** Схема БД и партиции. */
    private Schema schema = new Schema();

    /** Свойства {@code taskcomm.schema.*}. */
    @Getter
    @Setter
    public static class Schema {

        /**
         * Накатывать ли схему стартера при старте — только для тестов и локальной разработки,
         * там ставится always. Продовую схему не ведёт: для неё changelog
         * db/changelog/taskcomm/master.xml или тот же SQL своим инструментом.
         * embedded на PostgreSQL не срабатывает никогда.
         */
        private DatabaseInitializationMode initialize = DatabaseInitializationMode.NEVER;

        /** Партиции, один набор на всю схему. */
        private Partitions partitions = new Partitions();
    }

    /** Свойства {@code taskcomm.schema.partitions.*} (docs/adr/0080-partition-configuration.md). */
    @Getter
    @Setter
    public static class Partitions {

        /**
         * Ведёт ли партиции стартер. false — партиции создаёт и удаляет потребитель,
         * стартер только проверяет, что партиция на текущий момент есть и запас достаточен.
         */
        private boolean manage = true;

        /** Размер партиции. Выбирается один раз: сменить его на работающей базе нельзя. */
        private PartitionPeriod period = PartitionPeriod.MONTH;

        /** Сколько периодов вперёд держать созданными, не меньше одного. */
        private int createAhead = 3;

        /** Такт обслуживания партиций. */
        private Duration interval = Duration.ofHours(1);

        /** Удаление старых партиций. */
        private Drop drop = new Drop();
    }

    /** Свойства {@code taskcomm.schema.partitions.drop.*}. */
    @Getter
    @Setter
    public static class Drop {

        /**
         * Удалять ли старые партиции. Выключено по умолчанию. Партиция с незавершённой работой
         * не удаляется никогда. Несовместимо с manage: false.
         */
        private boolean enabled;

        /** Глубина хранения: партиция удаляется, когда её верхняя граница старше этого срока. */
        private Duration retention = Duration.ofDays(180);
    }
}
