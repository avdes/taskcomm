package xyz.avasilev.taskcomm.task.internal;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Движок задач экземпляра: поллеры со своими пулами воркеров и поток heartbeat
 * (docs/adr/0063-claim-ordering-and-pollers.md, docs/adr/0025-lease-mechanics.md).
 *
 * <p>Такты поллеров, heartbeat, подстраховочного поллера и подметальщика результатов детей идут на планировщике стартера — помеченном
 * {@code @TaskcommScheduler} или собственном, который движок заводит и останавливает сам; бином
 * он не регистрируется (docs/adr/0096-no-foreign-type-beans.md). Пулы воркеров — по одному
 * на поллер, их создаёт только стартер; потоки — из фабрики под {@code @TaskcommWorkerThreadFactory}
 * или собственной, каждый запуск оборачивается декоратором под {@code @TaskcommWorkerTaskDecorator}.
 *
 * <p>Остановка: поллеры перестают захватывать, воркеры дорабатывают не дольше
 * {@link #SHUTDOWN_TIMEOUT}, heartbeat продлевает их аренды до конца ожидания. Невернувшиеся
 * воркеры не прерываются — их попытки закроет переподхват на другом экземпляре: гарантия
 * at-least-once это допускает.
 */
@Slf4j
public class TaskEngine implements SmartLifecycle {

    /** Сколько ждать воркеров при остановке — в пределах умолчания Spring на фазу остановки. */
    static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(25);

    private static final String THREAD_PREFIX = "taskcomm-task-";

    private final List<TaskPoller> pollers;

    private final TaskHeartbeat heartbeat;

    private final TaskResultDiscard discard;

    private final TaskSafetyPoller safety;

    private final Duration safetyInterval;

    private final Duration heartbeatTick;

    private final TaskScheduler externalScheduler;

    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    private ThreadPoolTaskScheduler ownScheduler;

    private boolean running;

    /**
     * Собирает движок.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param ids генератор идентификаторов
     * @param settings итоговые настройки задач с раскладкой поллеров
     * @param registry реестр процессоров
     * @param codec кодек нагрузок и результатов
     * @param threadFactory фабрика потоков воркеров потребителя или {@code null}
     * @param decorator декоратор запусков воркеров потребителя или {@code null}
     * @param scheduler планировщик потребителя или {@code null} — тогда движок заводит свой
     */
    public TaskEngine(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final UuidV7 ids,
            final TaskSettings settings,
            final TaskProcessorRegistry registry,
            final TaskPayloadCodec codec,
            final ThreadFactory threadFactory,
            final TaskDecorator decorator,
            final TaskScheduler scheduler) {
        final PollerLayout layout = settings.pollers();
        final String instance = ManagementFactory.getRuntimeMXBean().getName();
        final TaskClaimer claimer = new TaskClaimer(
                dataSource,
                transactionManager,
                ids,
                settings,
                layout,
                () -> ThreadLocalRandom.current().nextDouble(),
                instance);
        final TaskChildren children = new TaskChildren(dataSource, transactionManager, layout);
        this.discard = new TaskResultDiscard(dataSource, transactionManager, settings.discard());
        this.safety = new TaskSafetyPoller(
                dataSource,
                transactionManager,
                children,
                new TaskCancellation(dataSource, transactionManager, children),
                settings.safety());
        this.safetyInterval = settings.safety().interval();
        final TaskAttemptCloser closer = new TaskAttemptCloser(
                dataSource,
                transactionManager,
                ids,
                settings,
                layout,
                () -> ThreadLocalRandom.current().nextDouble(),
                children,
                this.discard);
        this.heartbeat = new TaskHeartbeat(dataSource, transactionManager);
        this.heartbeatTick = settings.heartbeatTick();
        this.externalScheduler = scheduler;
        final TaskWorker worker = new TaskWorker(
                registry, codec, closer, this.heartbeat, children, new TaskCollector(dataSource, codec), ids);
        final List<PollerSettings> all = new ArrayList<>();
        all.add(layout.main());
        all.addAll(layout.dedicated());
        this.pollers = all.stream()
                .map(poller -> new TaskPoller(
                        poller,
                        claimable(layout, poller, registry),
                        claimer,
                        worker,
                        threadFactory != null ? threadFactory : workerThreads(poller),
                        decorator))
                .toList();
    }

    @Override
    public synchronized void start() {
        if (this.running) {
            return;
        }
        final TaskScheduler scheduler = this.externalScheduler != null ? this.externalScheduler : this.createOwn();
        for (final TaskPoller poller : this.pollers) {
            final Duration interval = poller.settings().interval();
            this.futures.add(scheduler.scheduleWithFixedDelay(poller::poll, Instant.now(), interval));
        }
        this.futures.add(scheduler.scheduleWithFixedDelay(
                this::renewLeases, Instant.now().plus(this.heartbeatTick), this.heartbeatTick));
        this.futures.add(scheduler.scheduleWithFixedDelay(
                this::runSafety, Instant.now().plus(this.safetyInterval), this.safetyInterval));
        if (this.discard.enabled()) {
            this.futures.add(scheduler.scheduleWithFixedDelay(
                    this::sweepResults,
                    Instant.now().plus(TaskResultDiscard.SWEEP_INTERVAL),
                    TaskResultDiscard.SWEEP_INTERVAL));
        }
        this.running = true;
    }

    @Override
    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.futures.subList(0, this.pollers.size()).forEach(future -> future.cancel(false));
        for (final TaskPoller poller : this.pollers) {
            if (!poller.stop(SHUTDOWN_TIMEOUT)) {
                log.warn(
                        "Поллер задач '{}': воркеры не вернулись за {}. Их попытки закроет переподхват истёкшей "
                                + "аренды, работа выполнится повторно.",
                        poller.settings().name(),
                        SHUTDOWN_TIMEOUT);
            }
        }
        this.futures.forEach(future -> future.cancel(false));
        this.futures.clear();
        if (this.ownScheduler != null) {
            this.ownScheduler.shutdown();
            this.ownScheduler = null;
        }
        this.running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return this.running;
    }

    private void renewLeases() {
        try {
            this.heartbeat.renewDue(this.heartbeatTick);
            this.heartbeat.refreshCancellations();
        } catch (final RuntimeException failure) {
            log.warn("Heartbeat задач: продление аренд не удалось, повтор на следующем такте", failure);
        }
    }

    private void runSafety() {
        try {
            this.safety.tick();
        } catch (final RuntimeException failure) {
            log.warn("Подстраховочный поллер задач: такт не удался, повтор на следующем", failure);
        }
    }

    private void sweepResults() {
        try {
            this.discard.sweep();
        } catch (final RuntimeException failure) {
            log.warn("Удаление результатов детей не удалось, повтор на следующем такте", failure);
        }
    }

    private ThreadPoolTaskScheduler createOwn() {
        final ThreadPoolTaskScheduler own = new ThreadPoolTaskScheduler();
        own.setPoolSize(2);
        own.setThreadNamePrefix(THREAD_PREFIX + "scheduler-");
        own.setDaemon(true);
        own.initialize();
        this.ownScheduler = own;
        return own;
    }

    /** Типы, которые поллер вправе захватывать, с формами контрактов их процессоров. */
    private static Map<String, ContractShape> claimable(
            final PollerLayout layout, final PollerSettings poller, final TaskProcessorRegistry registry) {
        final Map<String, ContractShape> shapes = registry.shapes();
        final Map<String, ContractShape> result = new LinkedHashMap<>();
        layout.claimableTypes(poller, shapes.keySet()).forEach(type -> result.put(type, shapes.get(type)));
        return result;
    }

    private static ThreadFactory workerThreads(final PollerSettings poller) {
        final CustomizableThreadFactory factory = new CustomizableThreadFactory(THREAD_PREFIX + poller.name() + "-");
        factory.setDaemon(true);
        return factory;
    }
}
