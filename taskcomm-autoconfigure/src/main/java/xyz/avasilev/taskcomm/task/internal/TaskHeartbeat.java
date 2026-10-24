package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Heartbeat экземпляра: продлевает аренды всех попыток, которые сейчас выполняет этот экземпляр,
 * одним запросом на такт (docs/adr/0025-lease-mechanics.md). Продлевает не воркер, а отдельный
 * поток: поток воркера занят синхронным кодом потребителя.
 *
 * <p>Продление трогает <b>только строки попыток</b>, сущность не обновляется вовсе, и
 * {@code updated_at} попытки тоже не двигается: продление — не изменение состояния. Условие —
 * токен и {@code finished_at IS NULL}, как у завершения. Предел обработки — счётчик продлений:
 * дошли до предела — попытка больше не продлевается, аренда истекает, и попытку закрывает
 * переподхват.
 *
 * <p>Попытка, которую продлить не удалось — закрыта переподхватом или дошла до предела, —
 * из реестра уходит и считается потерянной: {@code shouldStop()} её контекста отвечает
 * {@code true}, не спрашивая базу.
 */
class TaskHeartbeat {

    private static final String RENEW = """
            UPDATE tc_task_attempt a
               SET locked_until = now() + make_interval(secs => l.lease),
                   lease_renewals = a.lease_renewals + 1
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:attemptId AS uuid[]),
                          CAST(:leaseToken AS uuid[]), CAST(:maxRenewals AS integer[]),
                          CAST(:lease AS double precision[]))
                   AS l(execution_created_at, id, lease_token, max_renewals, lease)
             WHERE a.execution_created_at >= :oldest
               AND a.execution_created_at = l.execution_created_at
               AND a.id = l.id
               AND a.lease_token = l.lease_token
               AND a.finished_at IS NULL
               AND a.lease_renewals < l.max_renewals
            RETURNING a.id
            """;

    /** Попытки, чью сущность или её предка отменили: отметка своей сущности и всех предков. */
    private static final String CANCELLED = """
            SELECT x.attempt_id
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:attemptId AS uuid[]),
                          CAST(:level AS text[]), CAST(:targetId AS uuid[]),
                          CAST(:taskId AS uuid[]), CAST(:batchId AS uuid[]))
                   AS x(execution_created_at, attempt_id, level, target_id, task_id, batch_id)
             WHERE (x.level = 'TASK' AND EXISTS (SELECT 1 FROM tc_task e
                        WHERE e.execution_created_at = x.execution_created_at AND e.id = x.target_id
                          AND e.cancel_requested_at IS NOT NULL))
                OR (x.level = 'BATCH' AND EXISTS (SELECT 1 FROM tc_task_batch e
                        WHERE e.execution_created_at = x.execution_created_at AND e.id = x.target_id
                          AND e.cancel_requested_at IS NOT NULL))
                OR (x.level = 'UNIT' AND EXISTS (SELECT 1 FROM tc_task_unit e
                        WHERE e.execution_created_at = x.execution_created_at AND e.id = x.target_id
                          AND e.cancel_requested_at IS NOT NULL))
                OR EXISTS (SELECT 1 FROM tc_task t
                        WHERE t.execution_created_at = x.execution_created_at AND t.id = x.task_id
                          AND t.cancel_requested_at IS NOT NULL)
                OR EXISTS (SELECT 1 FROM tc_task_batch b
                        WHERE b.execution_created_at = x.execution_created_at AND b.id = x.batch_id
                          AND b.cancel_requested_at IS NOT NULL)
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final Map<UUID, ClaimedAttempt> active = new ConcurrentHashMap<>();

    private final Set<UUID> lost = ConcurrentHashMap.newKeySet();

    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

    /** Когда попытка продлена последний раз — по монотонным часам экземпляра, не базы. */
    private final Map<UUID, Long> renewedAt = new ConcurrentHashMap<>();

    private final LongSupplier nanoClock;

    /**
     * Собирает heartbeat.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     */
    TaskHeartbeat(final DataSource dataSource, final PlatformTransactionManager transactionManager) {
        this(dataSource, transactionManager, System::nanoTime);
    }

    /**
     * Собирает heartbeat с заданными монотонными часами — для тестов расписания продлений.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param nanoClock монотонные часы в наносекундах
     */
    TaskHeartbeat(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Берёт попытку под продление — сразу после захвата.
     *
     * @param attempt захваченная работа
     */
    void track(final ClaimedAttempt attempt) {
        this.lost.remove(attempt.lease().attemptId());
        this.renewedAt.put(attempt.lease().attemptId(), this.nanoClock.getAsLong());
        this.active.put(attempt.lease().attemptId(), attempt);
    }

    /**
     * Снимает попытку с продления — после её закрытия, успешного или нет.
     *
     * @param attempt захваченная работа
     */
    void release(final ClaimedAttempt attempt) {
        this.active.remove(attempt.lease().attemptId());
        this.renewedAt.remove(attempt.lease().attemptId());
        this.cancelled.remove(attempt.lease().attemptId());
        this.lost.remove(attempt.lease().attemptId());
    }

    /**
     * Потеряна ли аренда попытки: последнее продление её не застало. Ответ из памяти.
     *
     * @param attempt захваченная работа
     * @return {@code true}, если продлевать попытку больше нельзя
     */
    boolean isLost(final ClaimedAttempt attempt) {
        return this.lost.contains(attempt.lease().attemptId());
    }

    /**
     * Отменена ли сущность попытки или её предок — по флагам, принесённым последним тактом
     * (docs/adr/0070-cancellation-on-any-entity.md). Ответ из памяти; об отмене узнают через такт.
     *
     * @param attempt захваченная работа
     * @return {@code true}, если отмена пришла
     */
    boolean isCancelled(final ClaimedAttempt attempt) {
        return this.cancelled.contains(attempt.lease().attemptId());
    }

    /**
     * Приносит в память флаги отмены сущностей всех выполняемых попыток и их предков — одним
     * неблокирующим запросом на такт.
     */
    void refreshCancellations() {
        final List<ClaimedAttempt> attempts = List.copyOf(this.active.values());
        if (!attempts.isEmpty()) {
            this.jdbc
                    .sql(CANCELLED)
                    .param(
                            "executionCreatedAt",
                            nullable(attempts, a -> TaskSql.parameter(a.target().executionCreatedAt())))
                    .param("attemptId", nullable(attempts, a -> a.lease().attemptId()))
                    .param("level", nullable(attempts, a -> a.target().level()))
                    .param("targetId", nullable(attempts, a -> a.target().id()))
                    .param(
                            "taskId",
                            nullable(
                                    attempts,
                                    a -> a.entity().parent() == null
                                            ? null
                                            : a.entity().parent().taskId()))
                    .param(
                            "batchId",
                            nullable(
                                    attempts,
                                    a -> a.entity().parent() == null
                                            ? null
                                            : a.entity().parent().batchId()))
                    .query(UUID.class)
                    .list()
                    .forEach(this.cancelled::add);
        }
    }

    /**
     * Продлевает все попытки реестра одним запросом, не глядя на их интервалы.
     *
     * @return попытки, которые продлить не удалось и которые сняты с продления
     */
    Set<UUID> renew() {
        return this.renew(List.copyOf(this.active.values()));
    }

    /**
     * Один такт потока heartbeat: продлевает одним запросом те попытки, чей интервал heartbeat
     * истёк бы раньше следующего такта. Интервал свой у каждого метода процессора, а поток один
     * на экземпляр и тикает с самым коротким интервалом; продление поэтому приходит не позже
     * своего интервала, а предел обработки в продлениях не тратится чаще, чем задумано.
     *
     * @param tick период такта потока heartbeat
     * @return попытки, которые продлить не удалось и которые сняты с продления
     */
    Set<UUID> renewDue(final Duration tick) {
        final long now = this.nanoClock.getAsLong();
        return this.renew(this.active.values().stream()
                .filter(attempt ->
                        now - this.renewedAt.getOrDefault(attempt.lease().attemptId(), now) + tick.toNanos()
                                > attempt.lease().heartbeatInterval().toNanos())
                .toList());
    }

    private Set<UUID> renew(final List<ClaimedAttempt> attempts) {
        if (attempts.isEmpty()) {
            return Set.of();
        }
        final Instant oldest = attempts.stream()
                .map(attempt -> attempt.target().executionCreatedAt())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        final List<UUID> renewed = this.transactions.execute(status -> {
            TaskSql.forceCustomPlan(this.jdbc);
            return this.jdbc
                    .sql(RENEW)
                    .param("oldest", TaskSql.parameter(oldest))
                    .param(
                            "executionCreatedAt",
                            column(attempts, a -> TaskSql.parameter(a.target().executionCreatedAt())))
                    .param("attemptId", column(attempts, a -> a.lease().attemptId()))
                    .param("leaseToken", column(attempts, a -> a.lease().token()))
                    .param("maxRenewals", column(attempts, a -> a.lease().maxRenewals()))
                    .param(
                            "lease",
                            column(attempts, a -> TaskSql.seconds(a.lease().duration())))
                    .query(UUID.class)
                    .list();
        });
        final Set<UUID> notRenewed = new HashSet<>();
        attempts.forEach(attempt -> notRenewed.add(attempt.lease().attemptId()));
        final long renewedNow = this.nanoClock.getAsLong();
        if (renewed != null) {
            renewed.forEach(notRenewed::remove);
            renewed.forEach(id -> this.renewedAt.computeIfPresent(id, (key, previous) -> renewedNow));
        }
        notRenewed.forEach(id -> {
            this.renewedAt.remove(id);
            if (this.active.remove(id) != null) {
                this.lost.add(id);
            }
        });
        return Set.copyOf(notRenewed);
    }

    /**
     * Столбец значений для {@code unnest}: массив строк, который запрос приводит к нужному типу.
     * Драйвер передаёт {@code String[]} как {@code text[]} без помощи Spring.
     */
    private static String[] column(final List<ClaimedAttempt> attempts, final Function<ClaimedAttempt, ?> value) {
        return attempts.stream().map(value).map(String::valueOf).toArray(String[]::new);
    }

    /** Столбец, в котором бывают пустые значения: они уходят как {@code NULL}, а не как текст. */
    private static String[] nullable(final List<ClaimedAttempt> attempts, final Function<ClaimedAttempt, ?> value) {
        return attempts.stream()
                .map(value)
                .map(item -> item == null ? null : String.valueOf(item))
                .toArray(String[]::new);
    }
}
