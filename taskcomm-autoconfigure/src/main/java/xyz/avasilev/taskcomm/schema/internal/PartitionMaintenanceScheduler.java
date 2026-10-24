package xyz.avasilev.taskcomm.schema.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Такт обслуживания партиций на планировщике стартера — без {@code @EnableScheduling}.
 *
 * <p>Планировщик — либо бин потребителя под {@code @TaskcommScheduler}, либо собственный, который
 * создаётся при запуске и останавливается здесь же; бином он не регистрируется
 * (docs/adr/0096-no-foreign-type-beans.md). Чужой планировщик не останавливается — он принадлежит
 * потребителю.
 */
@Slf4j
public class PartitionMaintenanceScheduler implements SmartLifecycle {

    private static final String THREAD_NAME_PREFIX = "taskcomm-partitions-";

    private final PartitionMaintenance maintenance;
    private final Duration interval;
    private final TaskScheduler externalScheduler;

    private ThreadPoolTaskScheduler ownScheduler;
    private ScheduledFuture<?> future;

    /**
     * Создаёт такт обслуживания.
     *
     * @param maintenance обслуживание партиций
     * @param interval {@code taskcomm.schema.partitions.interval}
     * @param externalScheduler планировщик под {@code @TaskcommScheduler} или {@code null}
     */
    public PartitionMaintenanceScheduler(
            final PartitionMaintenance maintenance, final Duration interval, final TaskScheduler externalScheduler) {
        this.maintenance = maintenance;
        this.interval = interval;
        this.externalScheduler = externalScheduler;
        warnIfSingleThreaded(externalScheduler);
    }

    @Override
    public synchronized void start() {
        if (this.future != null) {
            return;
        }
        final TaskScheduler scheduler = this.externalScheduler != null ? this.externalScheduler : this.createOwn();
        this.future = scheduler.scheduleWithFixedDelay(
                this.maintenance::tick, Instant.now().plus(this.interval), this.interval);
    }

    @Override
    public synchronized void stop() {
        if (this.future != null) {
            this.future.cancel(false);
            this.future = null;
        }
        if (this.ownScheduler != null) {
            this.ownScheduler.shutdown();
            this.ownScheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return this.future != null;
    }

    private ThreadPoolTaskScheduler createOwn() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setDaemon(true);
        scheduler.initialize();
        this.ownScheduler = scheduler;
        return scheduler;
    }

    private static void warnIfSingleThreaded(final TaskScheduler scheduler) {
        if (scheduler instanceof final ThreadPoolTaskScheduler pool) {
            final int threads = configuredThreads(pool);
            if (threads < 2) {
                log.warn(
                        "Планировщик под @TaskcommScheduler работает в {} поток(е). На нём идут такты taskcomm, "
                                + "а с движками — heartbeat: занятый планировщик задерживает продление аренды, "
                                + "и работа выполняется дважды. Дайте ему не меньше двух потоков.",
                        threads);
            }
        }
    }

    /**
     * Заданное число потоков, а не число уже созданных: у свежего пула их ноль.
     *
     * @param pool планировщик потребителя
     * @return размер ядра пула
     */
    private static int configuredThreads(final ThreadPoolTaskScheduler pool) {
        try {
            return pool.getScheduledThreadPoolExecutor().getCorePoolSize();
        } catch (final IllegalStateException notInitialized) {
            return pool.getPoolSize();
        }
    }
}
