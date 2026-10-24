package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;

/**
 * Поллер задач: свой пул воркеров размером {@code workers} и цикл захвата
 * (docs/adr/0063-claim-ordering-and-pollers.md).
 *
 * <p><b>Захват — не больше свободных воркеров</b>, {@code batch-size} — только верхняя граница
 * (docs/adr/0083-claim-limited-by-free-workers.md). Свободных нет — в базу не ходит. Захват вернул
 * столько, сколько просили, — следующий идёт сразу, как освободится воркер: работа в очереди,
 * скорее всего, ещё есть. Меньше — очередь пуста, следующий по такту. Такт — пауза для пустой
 * очереди, а не потолок пропускной способности.
 *
 * <p>Захваты одного поллера не идут параллельно: запрос, пришедший во время захвата, выполняется
 * сразу после него, а не теряется.
 */
@Slf4j
class TaskPoller {

    private final PollerSettings settings;

    private final Map<String, ContractShape> types;

    private final TaskClaimer claimer;

    private final TaskWorker worker;

    private final ThreadPoolExecutor pool;

    private final Semaphore free;

    private final AtomicBoolean polling = new AtomicBoolean();

    private final AtomicBoolean requested = new AtomicBoolean();

    private volatile boolean queueMayHaveMore;

    private volatile boolean running = true;

    /**
     * Собирает поллер.
     *
     * @param settings настройки поллера
     * @param types типы, которые поллер вправе захватывать, с формами контрактов
     * @param claimer захват
     * @param worker воркер
     * @param threadFactory фабрика потоков пула
     * @param decorator обёртка каждого запуска работы или {@code null}
     */
    TaskPoller(
            final PollerSettings settings,
            final Map<String, ContractShape> types,
            final TaskClaimer claimer,
            final TaskWorker worker,
            final ThreadFactory threadFactory,
            final TaskDecorator decorator) {
        this.settings = settings;
        this.types = Map.copyOf(types);
        this.claimer = claimer;
        this.worker = worker;
        this.free = new Semaphore(settings.workers());
        this.pool =
                new ThreadPoolExecutor(
                        settings.workers(),
                        settings.workers(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        threadFactory) {
                    @Override
                    public void execute(final Runnable command) {
                        super.execute(decorator == null ? command : decorator.decorate(command));
                    }
                };
    }

    PollerSettings settings() {
        return this.settings;
    }

    /**
     * Захватывает работу, пока есть свободные воркеры и полные захваты. Вызывается тактом
     * и освобождением воркера; параллельный вызов не ждёт, а оставляет запрос текущему.
     */
    void poll() {
        this.requested.set(true);
        while (this.requested.get() && this.polling.compareAndSet(false, true)) {
            try {
                this.requested.set(false);
                this.claimWhileFull();
            } finally {
                this.polling.set(false);
            }
        }
    }

    /**
     * Перестаёт захватывать и ждёт работающих воркеров не дольше {@code timeout}. Невернувшиеся
     * воркеры не прерываются: их попытки закроет переподхват, когда истечёт аренда.
     *
     * @param timeout сколько ждать
     * @return {@code true}, если все воркеры вернулись
     */
    boolean stop(final Duration timeout) {
        this.running = false;
        this.pool.shutdown();
        try {
            return this.pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void claimWhileFull() {
        boolean full = true;
        while (full && this.running && !this.types.isEmpty()) {
            final int ask = Math.min(this.settings.batchSize(), this.free.availablePermits());
            full = ask > 0 && this.free.tryAcquire(ask) && this.claim(ask);
        }
    }

    /**
     * Один захват под уже занятые {@code ask} воркеров; незанятых захваченным отпускает.
     *
     * @return {@code true}, если захвачено столько, сколько просили, — очередь, скорее всего, не пуста
     */
    private boolean claim(final int ask) {
        final List<ClaimedAttempt> claimed;
        try {
            claimed = this.claimer.claim(this.settings, this.types, ask);
        } catch (final RuntimeException failure) {
            this.free.release(ask);
            log.warn("Поллер задач '{}': захват не удался, повтор по такту", this.settings.name(), failure);
            return false;
        }
        this.free.release(ask - claimed.size());
        this.queueMayHaveMore = claimed.size() == ask;
        claimed.forEach(this::start);
        return this.queueMayHaveMore;
    }

    private void start(final ClaimedAttempt attempt) {
        try {
            this.pool.execute(() -> this.run(attempt));
        } catch (final RejectedExecutionException stopped) {
            this.free.release();
            log.debug(
                    "Поллер задач '{}' остановлен; попытку {} закроет переподхват истёкшей аренды",
                    this.settings.name(),
                    attempt.lease().attemptId());
        }
    }

    private void run(final ClaimedAttempt attempt) {
        try {
            this.worker.execute(attempt);
        } finally {
            this.free.release();
            if (this.queueMayHaveMore && this.running) {
                this.poll();
            }
        }
    }
}
