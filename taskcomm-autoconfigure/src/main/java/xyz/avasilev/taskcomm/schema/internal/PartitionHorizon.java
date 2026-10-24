package xyz.avasilev.taskcomm.schema.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.schema.internal.PartitionCatalog.ExistingPartition;

/**
 * Граница активных партиций — {@code tc_partition_horizon.active_from}
 * (docs/adr/0101-active-partition-horizon.md).
 *
 * <p>Здесь — только сдвиг вперёд: по одному диапазону за раз, под
 * {@code pg_try_advisory_xact_lock} по ключу диапазона и лишь когда в нём не осталось незавершённых
 * записей. Опускает границу назад оживление партиции — путь записи подсистемы, который берёт
 * разделяемую блокировку того же ключа. Партиции текущего и будущих периодов границей
 * не закрываются никогда.
 */
@Slf4j
@RequiredArgsConstructor
class PartitionHorizon {

    private static final String OLDEST_UNFINISHED_QUERY = """
            SELECT level, kind, id, status, execution_created_at FROM (
                (SELECT 'TASK' AS level, task_type AS kind, id, status, execution_created_at
                   FROM tc_task WHERE execution_created_at >= :from AND %s
                  ORDER BY execution_created_at LIMIT 1)
                UNION ALL
                (SELECT 'BATCH', task_type, id, status, execution_created_at
                   FROM tc_task_batch WHERE execution_created_at >= :from AND %s
                  ORDER BY execution_created_at LIMIT 1)
                UNION ALL
                (SELECT 'UNIT', task_type, id, status, execution_created_at
                   FROM tc_task_unit WHERE execution_created_at >= :from AND %s
                  ORDER BY execution_created_at LIMIT 1)
                UNION ALL
                (SELECT 'COMMUNICATION', coalesce(source, destination), id, status, execution_created_at
                   FROM tc_communication WHERE execution_created_at >= :from AND %s
                  ORDER BY execution_created_at LIMIT 1)
            ) oldest
            ORDER BY execution_created_at
            LIMIT 1
            """.formatted(
                    PartitionedTable.TASK.unfinishedCondition(),
                    PartitionedTable.TASK_BATCH.unfinishedCondition(),
                    PartitionedTable.TASK_UNIT.unfinishedCondition(),
                    PartitionedTable.COMMUNICATION.unfinishedCondition());

    private static final String RANGE_HAS_UNFINISHED_QUERY = "SELECT "
            + PartitionedTable.withStatus().stream()
                    .map(table -> "EXISTS (SELECT 1 FROM " + table.tableName()
                            + " WHERE execution_created_at >= :lower AND execution_created_at < :upper AND "
                            + table.unfinishedCondition() + ")")
                    .collect(Collectors.joining(" OR "));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RangeCalendar calendar;

    /**
     * Сдвигает границу вперёд, пока очередной диапазон ниже текущего периода свободен
     * от незавершённой работы. Нерассчитанную границу ({@code -infinity}) сначала поднимает
     * до самой старой существующей партиции.
     *
     * @param now время базы
     * @param partitions существующие партиции
     */
    void advance(final Instant now, final List<ExistingPartition> partitions) {
        final Instant current = this.calendar.floor(now);
        Optional<Instant> horizon = this.read();
        if (horizon.isEmpty()) {
            final Instant initial = partitions.stream()
                    .map(ExistingPartition::lower)
                    .filter(lower -> lower != null && lower.isBefore(current))
                    .map(this.calendar::floor)
                    .min(Comparator.naturalOrder())
                    .orElse(current);
            this.transactions.executeWithoutResult(status -> this.jdbc
                    .sql("UPDATE tc_partition_horizon SET active_from = :initial, updated_at = now()"
                            + " WHERE NOT isfinite(active_from)")
                    .param("initial", SchemaSql.parameter(initial))
                    .update());
            horizon = this.read();
        }
        Instant lower = horizon.map(this.calendar::floor).orElse(current);
        while (lower.isBefore(current) && this.moveOver(lower)) {
            lower = this.calendar.next(lower);
        }
    }

    /**
     * Предупреждает, если граница старше глубины хранения, и называет то, что её держит.
     *
     * @param now время базы
     * @param retention {@code taskcomm.schema.partitions.drop.retention}
     */
    void warnIfStale(final Instant now, final Duration retention) {
        final Optional<Instant> horizon = this.read();
        if (horizon.isEmpty() || !horizon.get().plus(retention).isBefore(now)) {
            return;
        }
        this.jdbc
                .sql(OLDEST_UNFINISHED_QUERY)
                .param("from", SchemaSql.parameter(horizon.get()))
                .query((rs, rowNum) -> {
                    log.warn(
                            "Граница активных партиций {} старше глубины хранения {}: её держит {} {} '{}' "
                                    + "в статусе {}, дело создано {}. Пока такая работа не завершена, захват "
                                    + "читает все партиции начиная с этой.",
                            horizon.get(),
                            retention,
                            rs.getString("level"),
                            rs.getString("id"),
                            rs.getString("kind"),
                            rs.getString("status"),
                            rs.getObject("execution_created_at", OffsetDateTime.class));
                    return rowNum;
                })
                .list();
    }

    /**
     * Текущая граница.
     *
     * @return граница или пусто, если она ещё не рассчитана
     */
    Optional<Instant> read() {
        return this.jdbc
                .sql("SELECT CASE WHEN isfinite(active_from) THEN active_from END AS active_from"
                        + " FROM tc_partition_horizon")
                .query((rs, rowNum) -> Optional.ofNullable(rs.getObject("active_from", OffsetDateTime.class)))
                .optional()
                .flatMap(value -> value)
                .map(OffsetDateTime::toInstant);
    }

    private boolean moveOver(final Instant lower) {
        final Instant upper = this.calendar.next(lower);
        return Boolean.TRUE.equals(this.transactions.execute(status -> {
            if (!SchemaSql.tryLock(this.jdbc, AdvisoryLocks.HORIZON_RANGE, RangeCalendar.rangeKey(lower))) {
                return false;
            }
            final boolean unfinished = Boolean.TRUE.equals(this.jdbc
                    .sql(RANGE_HAS_UNFINISHED_QUERY)
                    .param("lower", SchemaSql.parameter(lower))
                    .param("upper", SchemaSql.parameter(upper))
                    .query(Boolean.class)
                    .single());
            if (unfinished) {
                return false;
            }
            return this.jdbc
                            .sql("UPDATE tc_partition_horizon SET active_from = :upper, updated_at = now()"
                                    + " WHERE active_from = :lower")
                            .param("upper", SchemaSql.parameter(upper))
                            .param("lower", SchemaSql.parameter(lower))
                            .update()
                    == 1;
        }));
    }
}
