package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Граница активных партиций глазами подсистемы задач (docs/adr/0101-active-partition-horizon.md):
 * чтение для отсечения партиций в захвате и оживление старой партиции на путях записи. Сдвигает
 * границу вперёд код схемы; здесь она только читается и опускается.
 *
 * <p><b>Ключ блокировки диапазона повторяет ключ кода схемы в точности</b>: двухчастная форма,
 * первая часть {@code 0x74630002}, вторая — сутки от эпохи до нижней границы диапазона в UTC
 * (docs/rules/partitioning.md). Нижняя граница считается в SQL через {@code date_trunc}, общего
 * Java-кода со схемой нет. Разошедшийся ключ молча разрешил бы такту закрыть оживлённую партицию.
 */
@RequiredArgsConstructor
class TaskPartitionHorizon {

    /** Первая часть ключа блокировки диапазона — {@code 0x74630002}. */
    static final int HORIZON_RANGE_LOCK = 0x74630002;

    private static final String LOCK_RANGE_QUERY = """
            SELECT lower_bound, pg_advisory_xact_lock_shared(:key, range_key)::text AS locked
              FROM (SELECT local_lower AT TIME ZONE 'UTC' AS lower_bound,
                           (extract(epoch FROM local_lower) / 86400)::int AS range_key
                      FROM (SELECT date_trunc(:unit, CAST(:moment AS timestamptz) AT TIME ZONE 'UTC') AS local_lower) l
                   ) r
            """;

    private final JdbcClient jdbc;

    private final PartitionRange range;

    /**
     * Текущая граница — нижняя граница самой ранней партиции, где может быть незавершённая работа.
     * Нерассчитанная граница ({@code -infinity}) читается как {@link Instant#MIN}: активны все
     * партиции.
     *
     * @param jdbc клиент на {@code DataSource} стартера
     * @return граница
     */
    static Instant activeFrom(final JdbcClient jdbc) {
        return jdbc.sql("SELECT CASE WHEN isfinite(active_from) THEN active_from END FROM tc_partition_horizon")
                .query((rs, rowNum) -> {
                    final OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                    return value == null ? Instant.MIN : value.toInstant();
                })
                .single();
    }

    /**
     * Оживляет партицию: в незавершённое состояние переходит сущность с этим ключом
     * партиционирования. Берёт разделяемую блокировку диапазона и под ней опускает границу,
     * если та выше нижней границы диапазона. Обязан работать в транзакции записи — блокировка
     * и опущенная граница живут до её коммита.
     *
     * <p>Для текущего периода ничего не меняет: граница его не закрывает никогда.
     *
     * @param executionCreatedAt ключ партиционирования оживляемой сущности
     */
    void revive(final Instant executionCreatedAt) {
        final OffsetDateTime lower = this.jdbc
                .sql(LOCK_RANGE_QUERY)
                .param("key", HORIZON_RANGE_LOCK)
                .param("unit", this.range.truncUnit())
                .param("moment", TaskSql.parameter(executionCreatedAt))
                .query((rs, rowNum) -> rs.getObject("lower_bound", OffsetDateTime.class))
                .single();
        this.jdbc
                .sql("UPDATE tc_partition_horizon SET active_from = :lower, updated_at = now()"
                        + " WHERE active_from > :lower")
                .param("lower", lower)
                .update();
    }
}
