package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Подсистема задач. */
    private Task task = new Task();

    /** Удаление результатов детей задач после сборки. */
    private Results results = new Results();

    /** Белый список полей поиска по нагрузке. */
    private Search search = new Search();

    /**
     * Свойства {@code taskcomm.search.*} (docs/adr/0037-search-field-registry.md).
     */
    @Getter
    @Setter
    public static class Search {

        /**
         * Удалять при старте строки белого списка полей поиска, которых не объявляет ни один
         * процессор. Поиск по удалённому полю перестаёт находить старые нагрузки; сами нагрузки
         * не трогаются. Выключено по умолчанию: удаление не должно происходить оттого, что
         * обновили версию зависимости.
         */
        private boolean removeUndeclaredFields;
    }

    /**
     * Свойства {@code taskcomm.results.*}: когда удаляются результаты детей после успешной сборки
     * (docs/adr/0058-task-result-storage.md, docs/adr/0122-child-results-discard-sweep.md).
     */
    @Getter
    @Setter
    public static class Results {

        /**
         * Когда удалять результаты детей после успешной сборки: never — не удалять, они живут
         * до дропа партиции; immediately — сразу после сборки; after — через discard-after.
         * Удаляются результаты детей всех попыток раскрытия родителя, факт отмечается на родителе.
         */
        private ResultsDiscard discard = ResultsDiscard.AFTER;

        /** Через сколько после успешной сборки удалять результаты детей в режиме after. */
        private Duration discardAfter = Duration.ofDays(7);
    }

    /**
     * Свойства {@code taskcomm.task.*} (docs/adr/0060-retry-and-lease-configuration.md). Числа —
     * отправная точка, а не выверенная рекомендация.
     */
    @Getter
    @Setter
    public static class Task {

        /**
         * Выключатель подсистемы задач. Действует вместе с корневым taskcomm.enabled:
         * выключен любой из двух, и нет ни поллеров, ни heartbeat, ни TaskService
         * (docs/adr/0090-root-switch-disables-everything.md).
         */
        private boolean enabled = true;

        /** Основной поллер. */
        private Poll poll = new Poll();

        /** Аренда — глобальное значение по умолчанию для всех типов и фаз. */
        private Lease lease = new Lease();

        /** Повторы — глобальное значение по умолчанию для всех типов и фаз. */
        private Retry retry = new Retry();

        /**
         * Выделенные поллеры, ключ — имя поллера. Основной их типов не берёт; тип, названный
         * в двух поллерах, — падение при старте.
         */
        private Map<String, Poller> pollers = new LinkedHashMap<>();

        /**
         * Переопределения по типу задачи; ключ: taskType. Сильнее любой аннотации
         * на процессоре (docs/adr/0061-processor-configuration-annotations.md).
         */
        private Map<String, TaskType> types = new LinkedHashMap<>();

        /** Подстраховочный поллер: доводит шаги каскада, не доведённые из-за падения экземпляра. */
        private Safety safety = new Safety();
    }

    /**
     * Свойства {@code taskcomm.task.safety.*} (docs/adr/0126-safety-poller.md).
     */
    @Getter
    @Setter
    public static class Safety {

        /** Такт подстраховочного поллера. */
        private Duration interval = Duration.ofMinutes(1);

        /**
         * Сколько шаг каскада может не двигаться, прежде чем подстраховка его доведёт. Короче —
         * подстраховка дублирует работу живых воркеров, длиннее — застрявшее ждёт дольше.
         */
        private Duration grace = Duration.ofMinutes(1);
    }

    /**
     * Свойства типа задачи {@code taskcomm.task.types.<тип>.*}. Не заданное берётся с уровня
     * слабее: аннотация на методе, на классе, глобальное значение.
     */
    @Getter
    @Setter
    public static class TaskType {

        /**
         * Приоритет задач типа по умолчанию. Шкала по возрастанию: меньше значит важнее.
         * Задаётся только здесь, на уровне типа (docs/adr/0084-priority-only-per-type.md).
         */
        private Integer priority;

        /** Аренда типа. */
        private LeaseOverride lease = new LeaseOverride();

        /** Повторы типа. */
        private RetryOverride retry = new RetryOverride();

        /**
         * Переопределения по фазе: process, expand, collect; сильнее
         * уровня типа. Фаза покрывает все методы фазы: у процессора с группами раскрытие идёт
         * в expand и expandBatch.
         */
        private Map<String, TaskTypePhase> phases = new LinkedHashMap<>();
    }

    /** Свойства фазы типа {@code taskcomm.task.types.<тип>.phases.<фаза>.*}. */
    @Getter
    @Setter
    public static class TaskTypePhase {

        /**
         * Не задаётся: приоритет — свойство задачи, а не попытки. Заданное значение — падение
         * при старте (docs/adr/0084-priority-only-per-type.md); поле есть, чтобы ошибка не прошла
         * молча.
         */
        private Integer priority;

        /** Аренда фазы. */
        private LeaseOverride lease = new LeaseOverride();

        /** Повторы фазы. */
        private RetryOverride retry = new RetryOverride();
    }

    /** Аренда уровня типа или фазы; не заданное — с уровня слабее. */
    @Getter
    @Setter
    public static class LeaseOverride {

        /** На сколько выдаётся и продлевается аренда. */
        private Duration duration;

        /** Такт продления аренды. */
        private Duration heartbeatInterval;

        /** Предел обработки. */
        private Duration maxExecutionTime;
    }

    /** Повторы уровня типа или фазы; не заданное — с уровня слабее. */
    @Getter
    @Setter
    public static class RetryOverride {

        /** Лимит попыток фазы в цикле повторов. */
        private Integer maxAttempts;

        /** Первая задержка backoff. */
        private Duration initialDelay;

        /** Множитель backoff. */
        private Double multiplier;

        /** Верхняя граница задержки. */
        private Duration maxDelay;

        /** Доля джиттера в (0, 1]. */
        private Double jitter;
    }

    /** Свойства {@code taskcomm.task.poll.*} (docs/adr/0063-claim-ordering-and-pollers.md). */
    @Getter
    @Setter
    public static class Poll {

        /** Пауза между захватами при пустой очереди. Не потолок пропускной способности. */
        private Duration interval = Duration.ofSeconds(1);

        /** Верхняя граница одного захвата; захватывается не больше свободных воркеров. */
        private int batchSize = 100;

        /** Размер пула воркеров. */
        private int workers = 8;

        /** Форма захвата. */
        private ClaimOrder claimOrder = ClaimOrder.DEADLINE;

        /**
         * Шаг дедлайна: во сколько времени ожидания обходится один пункт приоритета.
         * При 1m приоритет 100 ждёт на 99 минут дольше приоритета 1.
         */
        private Duration deadlineStep = Duration.ofMinutes(1);

        /**
         * Доля захватов по чистому FIFO при форме quota, строго между 0 и 1. Каждый захват
         * идёт мимо приоритета с этой вероятностью (docs/adr/0115-quota-fifo-share-by-lottery.md).
         */
        private double fifoShare = 0.1;
    }

    /**
     * Свойства выделенного поллера {@code taskcomm.task.pollers.<имя>.*}. Не заданное значение
     * берётся у основного поллера.
     */
    @Getter
    @Setter
    public static class Poller {

        /** Типы задач поллера, хотя бы один. */
        private List<String> types = new ArrayList<>();

        /** Пауза между захватами при пустой очереди. */
        private Duration interval;

        /** Верхняя граница одного захвата. */
        private Integer batchSize;

        /** Размер пула воркеров. */
        private Integer workers;

        /** Форма захвата. */
        private ClaimOrder claimOrder;

        /** Шаг дедлайна. */
        private Duration deadlineStep;

        /** Доля захватов по чистому FIFO при форме quota. */
        private Double fifoShare;
    }

    /** Свойства {@code taskcomm.task.lease.*} (docs/adr/0025-lease-mechanics.md). */
    @Getter
    @Setter
    public static class Lease {

        /** На сколько выдаётся и продлевается аренда. */
        private Duration duration = Duration.ofSeconds(60);

        /** Такт продления аренды — порядка трети аренды, строго меньше её. */
        private Duration heartbeatInterval = Duration.ofSeconds(20);

        /** Предел обработки: дольше аренда не продлевается, и попытка считается неуспешной. */
        private Duration maxExecutionTime = Duration.ofMinutes(30);
    }

    /** Свойства {@code taskcomm.task.retry.*} (docs/adr/0009-retry-and-progress.md). */
    @Getter
    @Setter
    public static class Retry {

        /** Сколько попыток в цикле повторов, не меньше одной. */
        private int maxAttempts = 3;

        /** Задержка после первой неуспешной попытки. */
        private Duration initialDelay = Duration.ofSeconds(5);

        /** Во сколько раз растёт задержка с каждой следующей попыткой. */
        private double multiplier = 2.0;

        /** Потолок задержки. */
        private Duration maxDelay = Duration.ofHours(1);

        /** Доля случайного разброса задержки в обе стороны, в пределах (0, 1]; ноль запрещён. */
        private double jitter = 0.2;
    }

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
