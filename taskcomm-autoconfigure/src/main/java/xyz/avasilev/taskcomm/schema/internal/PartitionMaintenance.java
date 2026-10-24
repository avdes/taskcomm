package xyz.avasilev.taskcomm.schema.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.schema.internal.PartitionCatalog.ExistingPartition;

/**
 * Обслуживание партиций: создание, удаление, очистка реестра корреляции, сдвиг границы активных
 * партиций (docs/adr/0078-partition-maintenance.md, docs/adr/0080-partition-configuration.md,
 * docs/adr/0101-active-partition-horizon.md).
 *
 * <p>Обслуживает один экземпляр за такт: каждая транзакция начинается с advisory-блокировки
 * по постоянному ключу. При старте она блокирующая — второй стартующий экземпляр ждёт и находит
 * партиции созданными; на такте — {@code pg_try_advisory_xact_lock}, и не взявший её экземпляр
 * пропускает такт.
 */
@Slf4j
public class PartitionMaintenance {

    /** {@code lock_timeout} создания партиций. Не взяли блокировку — повтор на следующем такте. */
    static final String CREATE_LOCK_TIMEOUT = "1s";

    /**
     * {@code lock_timeout} удаления — постоянная, а не свойство: удаление держит девять
     * родительских таблиц, и большое значение превратило бы миллисекунды простоя в секунды.
     */
    static final String DROP_LOCK_TIMEOUT = "100ms";

    /** Размер пачки при очистке реестра корреляции после удаления партиций. */
    static final int CORRELATION_CLEANUP_BATCH = 1000;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PartitionSettings settings;
    private final PartitionCatalog catalog;
    private final PartitionHorizon horizon;

    /**
     * Создаёт обслуживание партиций.
     *
     * @param jdbc клиент на {@code DataSource} стартера
     * @param transactions транзакции стартера, {@code READ COMMITTED}
     * @param settings настройки партиций
     */
    public PartitionMaintenance(
            final JdbcClient jdbc, final TransactionTemplate transactions, final PartitionSettings settings) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.settings = settings;
        this.catalog = new PartitionCatalog(jdbc);
        this.horizon = new PartitionHorizon(jdbc, transactions, settings.calendar());
    }

    /**
     * Синхронная часть старта, до поллеров: проверка границ, партиции на текущий период и запас,
     * первый расчёт границы активных партиций.
     *
     * @throws TaskcommConfigurationException если границы не совпадают с размером, а партиции
     *     на текущий момент нет и создать её не удалось
     */
    public void startup() {
        final Instant now = SchemaSql.databaseNow(this.jdbc);
        if (this.settings.manage()) {
            this.verifyBoundaries(this.catalog.partitions());
            this.createAhead(now, true);
        } else {
            this.verifyCurrentCovered(now);
        }
        this.warnIfIntervalExceedsPeriod(now);
        this.warnIfShortAhead(now, this.catalog.partitions());
        this.horizon.advance(now, this.catalog.partitions());
    }

    /** Такт обслуживания. Исключения не выпускает: упавший такт не должен отменять следующие. */
    public void tick() {
        try {
            final Instant now = SchemaSql.databaseNow(this.jdbc);
            if (this.settings.manage()) {
                if (!this.createAhead(now, false)) {
                    log.debug("Партиции обслуживает другой экземпляр, такт пропущен");
                    return;
                }
                if (this.settings.dropEnabled()) {
                    this.dropExpired(now);
                }
            } else if (!this.covers(this.catalog.partitions(), now)) {
                log.error(
                        "Нет партиции на текущий момент {} — вставки в таблицы taskcomm падают. "
                                + "При taskcomm.schema.partitions.manage = false партиции создаёт потребитель.",
                        now);
            }
            final List<ExistingPartition> partitions = this.catalog.partitions();
            this.warnIfShortAhead(now, partitions);
            this.horizon.advance(now, partitions);
            this.horizon.warnIfStale(now, this.settings.retention());
        } catch (final RuntimeException e) {
            log.error("Такт обслуживания партиций не выполнен, повтор на следующем такте", e);
        }
    }

    /**
     * Создаёт недостающие партиции от текущего периода до {@code create-ahead} периодов вперёд,
     * по диапазону на транзакцию.
     *
     * @return {@code false}, если блокировку обслуживания держит другой экземпляр
     */
    private boolean createAhead(final Instant now, final boolean startup) {
        Instant lower = this.settings.calendar().floor(now);
        for (int index = 0; index <= this.settings.createAhead(); index++) {
            try {
                if (!this.createRange(lower, startup)) {
                    return false;
                }
            } catch (final DataAccessException e) {
                if (startup && index == 0) {
                    throw new TaskcommConfigurationException(
                            "Нет партиций taskcomm на текущий период " + lower + " и создать их не удалось: "
                                    + e.getMostSpecificCause().getMessage()
                                    + ". Проверьте права пользователя БД на DDL или создайте партиции сами "
                                    + "при taskcomm.schema.partitions.manage = false.",
                            e);
                }
                log.warn("Партиции taskcomm на диапазон с {} не созданы, повтор на следующем такте", lower, e);
                return true;
            }
            lower = this.settings.calendar().next(lower);
        }
        return true;
    }

    private boolean createRange(final Instant lower, final boolean blocking) {
        final Instant upper = this.settings.calendar().next(lower);
        return Boolean.TRUE.equals(this.transactions.execute(status -> {
            if (!this.lockMaintenance(blocking)) {
                return false;
            }
            final Set<String> covered = this.catalog.partitions().stream()
                    .filter(partition -> lower.equals(partition.lower()))
                    .map(ExistingPartition::parent)
                    .collect(Collectors.toSet());
            SchemaSql.setLocalLockTimeout(this.jdbc, CREATE_LOCK_TIMEOUT);
            for (final PartitionedTable table : PartitionedTable.values()) {
                if (!covered.contains(table.tableName())) {
                    this.createPartition(table, lower, upper);
                }
            }
            return true;
        }));
    }

    private void createPartition(final PartitionedTable table, final Instant lower, final Instant upper) {
        final String parent = SchemaSql.quote(table.tableName());
        final String partition = SchemaSql.quote(RangeCalendar.partitionName(table.tableName(), lower));
        this.jdbc
                .sql("CREATE TABLE IF NOT EXISTS " + partition + " (LIKE " + parent + " INCLUDING ALL)")
                .update();
        this.jdbc
                .sql("ALTER TABLE " + parent + " ATTACH PARTITION " + partition + " FOR VALUES FROM ("
                        + SchemaSql.literal(lower) + ") TO (" + SchemaSql.literal(upper) + ")")
                .update();
        log.info("Создана партиция {} на диапазон [{}, {})", partition, lower, upper);
    }

    private void dropExpired(final Instant now) {
        final Instant current = this.settings.calendar().floor(now);
        final Map<Instant, List<ExistingPartition>> ranges = this.catalog.partitions().stream()
                .filter(partition -> partition.matches(this.settings.calendar()))
                .collect(Collectors.groupingBy(ExistingPartition::lower, TreeMap::new, Collectors.toList()));
        // Диапазоны идут по возрастанию: первый неистёкший значит, что истёкших дальше нет.
        for (final Map.Entry<Instant, List<ExistingPartition>> range : ranges.entrySet()) {
            final Instant lower = range.getKey();
            final Instant upper = this.settings.calendar().next(lower);
            final boolean expired = lower.isBefore(current)
                    && upper.plus(this.settings.retention()).isBefore(now);
            final DropOutcome outcome = expired ? this.dropRange(range.getValue()) : DropOutcome.BUSY;
            if (outcome == DropOutcome.BUSY) {
                break;
            }
            if (outcome == DropOutcome.DROPPED) {
                this.cleanupCorrelation(lower, upper);
            }
        }
    }

    private DropOutcome dropRange(final List<ExistingPartition> partitions) {
        final List<ExistingPartition> ordered = this.inLockOrder(partitions);
        final String names =
                ordered.stream().map(p -> SchemaSql.quote(p.name())).collect(Collectors.joining(", "));
        try {
            return this.transactions.execute(status -> {
                if (!this.lockMaintenance(false)) {
                    return DropOutcome.BUSY;
                }
                SchemaSql.setLocalLockTimeout(this.jdbc, DROP_LOCK_TIMEOUT);
                this.jdbc
                        .sql("LOCK TABLE " + names + " IN ACCESS EXCLUSIVE MODE")
                        .update();
                if (this.hasUnfinished(ordered)) {
                    status.setRollbackOnly();
                    log.warn(
                            "Партиции {} старше глубины хранения, но в них есть незавершённая работа — "
                                    + "удаление отложено",
                            names);
                    return DropOutcome.KEPT;
                }
                this.jdbc.sql("DROP TABLE " + names).update();
                log.info("Удалены партиции {}", names);
                return DropOutcome.DROPPED;
            });
        } catch (final DataAccessException e) {
            if (!SchemaSql.isLockTimeout(e)) {
                throw e;
            }
            log.info("Партиции {} заняты, удаление отложено до следующего такта", names);
            return DropOutcome.BUSY;
        }
    }

    private List<ExistingPartition> inLockOrder(final List<ExistingPartition> partitions) {
        final List<ExistingPartition> ordered = new ArrayList<>();
        for (final PartitionedTable table : PartitionedTable.values()) {
            partitions.stream()
                    .filter(partition -> table.tableName().equals(partition.parent()))
                    .forEach(ordered::add);
        }
        return ordered;
    }

    private boolean hasUnfinished(final List<ExistingPartition> partitions) {
        final List<String> checks = new ArrayList<>();
        for (final ExistingPartition partition : partitions) {
            Arrays.stream(PartitionedTable.values())
                    .filter(table -> table.hasStatus() && table.tableName().equals(partition.parent()))
                    .forEach(table -> checks.add("EXISTS (SELECT 1 FROM " + SchemaSql.quote(partition.name())
                            + " WHERE " + table.unfinishedCondition() + ")"));
        }
        return !checks.isEmpty()
                && Boolean.TRUE.equals(this.jdbc
                        .sql("SELECT " + String.join(" OR ", checks))
                        .query(Boolean.class)
                        .single());
    }

    private void cleanupCorrelation(final Instant lower, final Instant upper) {
        Integer deleted;
        do {
            deleted = this.transactions.execute(status -> this.jdbc
                    .sql("""
                            DELETE FROM tc_communication_correlation
                             WHERE id IN (SELECT id FROM tc_communication_correlation
                                           WHERE execution_created_at >= :lower AND execution_created_at < :upper
                                           LIMIT :batch)
                            """)
                    .param("lower", SchemaSql.parameter(lower))
                    .param("upper", SchemaSql.parameter(upper))
                    .param("batch", CORRELATION_CLEANUP_BATCH)
                    .update());
        } while (deleted != null && deleted > 0);
    }

    private boolean lockMaintenance(final boolean blocking) {
        if (blocking) {
            SchemaSql.lock(this.jdbc, AdvisoryLocks.MAINTENANCE, AdvisoryLocks.MAINTENANCE_SUBKEY);
            return true;
        }
        return SchemaSql.tryLock(this.jdbc, AdvisoryLocks.MAINTENANCE, AdvisoryLocks.MAINTENANCE_SUBKEY);
    }

    private void verifyBoundaries(final List<ExistingPartition> partitions) {
        final List<String> mismatched = partitions.stream()
                .filter(partition -> !partition.matches(this.settings.calendar()))
                .map(partition -> partition.name() + " [" + partition.lower() + ", " + partition.upper() + ")")
                .toList();
        if (!mismatched.isEmpty()) {
            throw new TaskcommConfigurationException("Границы партиций не совпадают с taskcomm.schema.partitions"
                    + ".period = " + this.settings.calendar().name().toLowerCase(Locale.ROOT) + ": "
                    + String.join(", ", mismatched) + ". Размер партиции выбирается один раз — верните прежнее "
                    + "значение period.");
        }
    }

    private void verifyCurrentCovered(final Instant now) {
        final List<ExistingPartition> partitions = this.catalog.partitions();
        final List<String> missing = Arrays.stream(PartitionedTable.values())
                .map(PartitionedTable::tableName)
                .filter(table -> partitions.stream()
                        .noneMatch(partition -> table.equals(partition.parent()) && contains(partition, now)))
                .toList();
        if (!missing.isEmpty()) {
            throw new TaskcommConfigurationException("Нет партиции на текущий момент " + now + " у таблиц "
                    + String.join(", ", missing) + ". При taskcomm.schema.partitions.manage = false партиции "
                    + "создаёт потребитель — создайте их до старта приложения.");
        }
    }

    private boolean covers(final List<ExistingPartition> partitions, final Instant moment) {
        return Arrays.stream(PartitionedTable.values())
                .allMatch(table -> partitions.stream()
                        .anyMatch(partition ->
                                table.tableName().equals(partition.parent()) && contains(partition, moment)));
    }

    private void warnIfShortAhead(final Instant now, final List<ExistingPartition> partitions) {
        Instant target = this.settings.calendar().floor(now);
        for (int index = 0; index <= this.settings.createAhead(); index++) {
            target = this.settings.calendar().next(target);
        }
        for (final PartitionedTable table : PartitionedTable.values()) {
            final Instant coveredUntil = coveredUntil(partitions, table.tableName(), now);
            if (coveredUntil.isBefore(target)) {
                log.warn(
                        "Партиции таблицы {} покрывают время только до {}, а нужно до {} "
                                + "(taskcomm.schema.partitions.create-ahead = {}). Без партиции вставка падает.",
                        table.tableName(),
                        coveredUntil,
                        target,
                        this.settings.createAhead());
            }
        }
    }

    private void warnIfIntervalExceedsPeriod(final Instant now) {
        final Instant current = this.settings.calendar().floor(now);
        final Duration period =
                Duration.between(current, this.settings.calendar().next(current));
        if (this.settings.interval().compareTo(period) > 0) {
            log.warn(
                    "taskcomm.schema.partitions.interval = {} больше одного периода партиции ({}): запас партиций "
                            + "тратится быстрее, чем проверяется.",
                    this.settings.interval(),
                    period);
        }
    }

    private static Instant coveredUntil(
            final List<ExistingPartition> partitions, final String table, final Instant from) {
        Instant point = from;
        boolean advanced = true;
        while (advanced) {
            advanced = false;
            for (final ExistingPartition partition : partitions) {
                if (table.equals(partition.parent()) && contains(partition, point)) {
                    point = partition.upper();
                    advanced = true;
                }
            }
        }
        return point;
    }

    private static boolean contains(final ExistingPartition partition, final Instant moment) {
        return partition.lower() != null
                && partition.upper() != null
                && !moment.isBefore(partition.lower())
                && moment.isBefore(partition.upper());
    }

    /** Чем кончилась попытка удалить диапазон. */
    private enum DropOutcome {
        DROPPED,
        KEPT,
        BUSY
    }
}
