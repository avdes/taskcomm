package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.Map;

/**
 * Итоговые настройки подсистемы задач после разрешения по пяти уровням
 * (docs/adr/0061-processor-configuration-annotations.md): настройки попыток по типу и методу
 * процессора (docs/adr/0118-attempt-settings-per-processor-method.md) и приоритет по умолчанию
 * по типу (docs/adr/0084-priority-only-per-type.md). Собирается при старте один раз; все проверки
 * и печать итоговых значений — при сборке.
 */
public final class TaskSettings implements AttemptSettingsSource {

    /** Приоритет по умолчанию, если ни {@code yaml}, ни аннотация его не задают, — как у колонки. */
    static final int DEFAULT_PRIORITY = 100;

    private final AttemptSettings global;

    private final Map<Key, AttemptSettings> attempts;

    private final Map<String, Integer> priorities;

    private final Duration heartbeatTick;

    private final PollerLayout pollers;

    TaskSettings(
            final AttemptSettings global,
            final Map<Key, AttemptSettings> attempts,
            final Map<String, Integer> priorities,
            final Duration heartbeatTick,
            final PollerLayout pollers) {
        this.pollers = pollers;
        this.global = global;
        this.attempts = Map.copyOf(attempts);
        this.priorities = Map.copyOf(priorities);
        this.heartbeatTick = heartbeatTick;
    }

    /**
     * Разрешает настройки, проверяет их и печатает итог по каждому типу и методу.
     *
     * @param global глобальные значения подсистемы — уровень 5
     * @param yaml переопределения типов из {@code yaml} — уровни 1 и 2
     * @param registry процессоры — их аннотации дают уровни 3 и 4
     * @param pollers раскладка поллеров — такт поллера типа для предупреждения о короткой аренде
     * @return итоговые настройки
     * @throws xyz.avasilev.taskcomm.TaskcommConfigurationException если итоговое значение недопустимо
     */
    public static TaskSettings resolve(
            final AttemptSettings global,
            final Map<String, TypeOverrides> yaml,
            final TaskProcessorRegistry registry,
            final PollerLayout pollers) {
        return new TaskSettingsResolver(global, yaml, pollers).resolve(registry);
    }

    /**
     * Итоговые настройки попыток. Тип без процессора и без {@code yaml} получает глобальные значения:
     * так закрываются его брошенные попытки.
     *
     * @param taskType тип задачи
     * @param method метод процессора
     * @return настройки попыток
     */
    @Override
    public AttemptSettings settings(final String taskType, final ProcessorMethod method) {
        return this.attempts.getOrDefault(new Key(taskType, method), this.global);
    }

    /**
     * Приоритет задач типа по умолчанию.
     *
     * @param taskType тип задачи
     * @return приоритет, больше нуля
     */
    int priority(final String taskType) {
        return this.priorities.getOrDefault(taskType, DEFAULT_PRIORITY);
    }

    /**
     * Такт потока heartbeat — самый короткий итоговый интервал heartbeat. Поток один на экземпляр,
     * а интервалы свои у методов; каждую попытку он продлевает по её интервалу.
     *
     * @return период такта
     */
    Duration heartbeatTick() {
        return this.heartbeatTick;
    }

    /**
     * Раскладка поллеров, с которой разрешались настройки.
     *
     * @return раскладка
     */
    PollerLayout pollers() {
        return this.pollers;
    }

    /**
     * Ключ настроек попыток.
     *
     * @param taskType тип задачи
     * @param method метод процессора
     */
    record Key(String taskType, ProcessorMethod method) {}
}
