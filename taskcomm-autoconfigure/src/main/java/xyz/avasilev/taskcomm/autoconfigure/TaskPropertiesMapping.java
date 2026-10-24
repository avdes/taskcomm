package xyz.avasilev.taskcomm.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.internal.AttemptSettings;
import xyz.avasilev.taskcomm.task.internal.ClaimForm;
import xyz.avasilev.taskcomm.task.internal.LeaseOverride;
import xyz.avasilev.taskcomm.task.internal.LeaseSettings;
import xyz.avasilev.taskcomm.task.internal.PartitionRange;
import xyz.avasilev.taskcomm.task.internal.PollerLayout;
import xyz.avasilev.taskcomm.task.internal.PollerSettings;
import xyz.avasilev.taskcomm.task.internal.ResultDiscard;
import xyz.avasilev.taskcomm.task.internal.RetryOverride;
import xyz.avasilev.taskcomm.task.internal.RetryPolicy;
import xyz.avasilev.taskcomm.task.internal.SettingsOverride;
import xyz.avasilev.taskcomm.task.internal.TypeOverrides;

/**
 * Перевод свойств {@code taskcomm.task.*} во внутренние значения подсистемы задач. Проверки
 * значений живут во внутренних типах; здесь к сообщению добавляется путь свойства.
 */
final class TaskPropertiesMapping {

    private TaskPropertiesMapping() {}

    /**
     * Глобальные настройки попыток — уровень 5 разрешения.
     *
     * @param task свойства задач
     * @return настройки попыток
     * @throws TaskcommConfigurationException если значение недопустимо
     */
    static AttemptSettings global(final TaskcommProperties.Task task) {
        final TaskcommProperties.Retry retry = task.getRetry();
        final TaskcommProperties.Lease lease = task.getLease();
        return new AttemptSettings(
                withPath(
                        "taskcomm.task.retry",
                        () -> new RetryPolicy(
                                retry.getMaxAttempts(),
                                retry.getInitialDelay(),
                                retry.getMultiplier(),
                                retry.getMaxDelay(),
                                retry.getJitter())),
                withPath(
                        "taskcomm.task.lease",
                        () -> new LeaseSettings(
                                lease.getDuration(), lease.getHeartbeatInterval(), lease.getMaxExecutionTime())));
    }

    /**
     * Переопределения типов — уровни 1 и 2 разрешения.
     *
     * @param task свойства задач
     * @return тип задачи → переопределения
     */
    static Map<String, TypeOverrides> types(final TaskcommProperties.Task task) {
        final Map<String, TypeOverrides> result = new LinkedHashMap<>();
        task.getTypes().forEach((type, settings) -> {
            final Map<String, SettingsOverride> phases = new LinkedHashMap<>();
            settings.getPhases()
                    .forEach((phase, phaseSettings) -> phases.put(
                            phase,
                            new SettingsOverride(
                                    phaseSettings.getPriority(),
                                    lease(phaseSettings.getLease()),
                                    retry(phaseSettings.getRetry()))));
            result.put(
                    type,
                    new TypeOverrides(
                            new SettingsOverride(
                                    settings.getPriority(), lease(settings.getLease()), retry(settings.getRetry())),
                            phases));
        });
        return result;
    }

    /**
     * Раскладка поллеров: основной и выделенные; не заданное у выделенного берётся у основного.
     *
     * @param task свойства задач
     * @return раскладка
     * @throws TaskcommConfigurationException если поллер настроен недопустимо или тип назван
     *     в двух поллерах
     */
    static PollerLayout pollers(final TaskcommProperties.Task task) {
        final TaskcommProperties.Poll main = task.getPoll();
        final PollerSettings mainPoller = withPath(
                "taskcomm.task.poll",
                () -> new PollerSettings(
                        PollerSettings.MAIN,
                        Set.of(),
                        main.getInterval(),
                        main.getBatchSize(),
                        main.getWorkers(),
                        ClaimForm.valueOf(main.getClaimOrder().name()),
                        main.getDeadlineStep(),
                        main.getFifoShare()));
        final List<PollerSettings> dedicated = task.getPollers().entrySet().stream()
                .map(entry -> dedicated(entry.getKey(), entry.getValue(), main))
                .toList();
        return withPath("taskcomm.task.pollers", () -> new PollerLayout(mainPoller, dedicated));
    }

    /**
     * Удаление результатов детей после сборки.
     *
     * @param results свойства {@code taskcomm.results}
     * @return настройка во внутреннем виде
     */
    static ResultDiscard discard(final TaskcommProperties.Results results) {
        return withPath(
                "taskcomm.results",
                () -> new ResultDiscard(
                        ResultDiscard.Mode.valueOf(results.getDiscard().name()), results.getDiscardAfter()));
    }

    /**
     * Детализация партиций для оживления старой партиции при записи.
     *
     * @param period период партиций из свойств
     * @return тот же период во внутреннем виде
     */
    static PartitionRange range(final PartitionPeriod period) {
        return PartitionRange.valueOf(period.name());
    }

    private static PollerSettings dedicated(
            final String name, final TaskcommProperties.Poller poller, final TaskcommProperties.Poll main) {
        return withPath(
                "taskcomm.task.pollers." + name,
                () -> new PollerSettings(
                        name,
                        Set.copyOf(poller.getTypes()),
                        poller.getInterval() != null ? poller.getInterval() : main.getInterval(),
                        poller.getBatchSize() != null ? poller.getBatchSize() : main.getBatchSize(),
                        poller.getWorkers() != null ? poller.getWorkers() : main.getWorkers(),
                        ClaimForm.valueOf(
                                (poller.getClaimOrder() != null ? poller.getClaimOrder() : main.getClaimOrder())
                                        .name()),
                        poller.getDeadlineStep() != null ? poller.getDeadlineStep() : main.getDeadlineStep(),
                        poller.getFifoShare() != null ? poller.getFifoShare() : main.getFifoShare()));
    }

    private static LeaseOverride lease(final TaskcommProperties.LeaseOverride lease) {
        return new LeaseOverride(lease.getDuration(), lease.getHeartbeatInterval(), lease.getMaxExecutionTime());
    }

    private static RetryOverride retry(final TaskcommProperties.RetryOverride retry) {
        return new RetryOverride(
                retry.getMaxAttempts(),
                retry.getInitialDelay(),
                retry.getMultiplier(),
                retry.getMaxDelay(),
                retry.getJitter());
    }

    private static <T> T withPath(final String path, final Supplier<T> value) {
        try {
            return value.get();
        } catch (final TaskcommConfigurationException invalid) {
            throw new TaskcommConfigurationException(path + ": " + invalid.getMessage(), invalid);
        }
    }
}
