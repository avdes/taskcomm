package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Настройки попыток и поллеров для интеграционных тестов подсистемы задач. */
final class TaskTestSettings {

    private TaskTestSettings() {}

    /** Раскладка из одного основного поллера с шагом дедлайна в минуту. */
    static PollerLayout layout() {
        return new PollerLayout(mainPoller(ClaimForm.DEADLINE), List.of());
    }

    static PollerSettings mainPoller(final ClaimForm form) {
        return new PollerSettings(
                PollerSettings.MAIN, Set.of(), Duration.ofSeconds(1), 100, 8, form, Duration.ofMinutes(1), 0.1);
    }

    /**
     * Настройки попыток для тестов.
     *
     * @param maxAttempts лимит попыток
     * @param initialDelay задержка первого повтора
     * @param lease аренда
     * @return настройки
     */
    static AttemptSettings settings(final int maxAttempts, final Duration initialDelay, final Duration lease) {
        return new AttemptSettings(
                new RetryPolicy(maxAttempts, initialDelay, 2.0, Duration.ofHours(1), 0.2),
                new LeaseSettings(lease, lease.dividedBy(4), lease.multipliedBy(3)));
    }
}
