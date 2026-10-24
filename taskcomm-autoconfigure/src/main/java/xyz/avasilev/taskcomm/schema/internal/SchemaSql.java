package xyz.avasilev.taskcomm.schema.internal;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Мелкие общие приёмы SQL кода схемы: время базы, advisory-блокировки, литералы DDL.
 *
 * <p>DDL не принимает параметров, поэтому имена и границы партиций подставляются в текст.
 * Имена — из перечня таблиц или из каталога и всегда в кавычках, границы — из {@link Instant},
 * а не из ввода.
 */
final class SchemaSql {

    /** SQLSTATE {@code lock_not_available}: в том числе истёкший {@code lock_timeout}. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private SchemaSql() {}

    /**
     * Текущее время базы — единственный источник времени для сравнений в SQL
     * (docs/adr/0079-database-clock.md).
     *
     * @param jdbc клиент на {@code DataSource} стартера
     * @return {@code now()} базы
     */
    static Instant databaseNow(final JdbcClient jdbc) {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    /**
     * Берёт транзакционную advisory-блокировку, не дожидаясь её.
     *
     * @param jdbc клиент в открытой транзакции
     * @param key первая часть ключа
     * @param subkey вторая часть ключа
     * @return {@code true}, если блокировка взята
     */
    static boolean tryLock(final JdbcClient jdbc, final int key, final int subkey) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key, :subkey)")
                .param("key", key)
                .param("subkey", subkey)
                .query(Boolean.class)
                .single());
    }

    /**
     * Берёт транзакционную advisory-блокировку, дожидаясь её.
     *
     * @param jdbc клиент в открытой транзакции
     * @param key первая часть ключа
     * @param subkey вторая часть ключа
     */
    static void lock(final JdbcClient jdbc, final int key, final int subkey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key, :subkey)::text")
                .param("key", key)
                .param("subkey", subkey)
                .query(String.class)
                .single();
    }

    /**
     * Ставит {@code lock_timeout} только на текущую транзакцию: соединение принадлежит пулу
     * потребителя, его сессию трогать нельзя.
     *
     * @param jdbc клиент в открытой транзакции
     * @param timeout значение в формате PostgreSQL, например {@code 100ms}
     */
    static void setLocalLockTimeout(final JdbcClient jdbc, final String timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout)
                .query(String.class)
                .single();
    }

    /**
     * Сработал ли {@code lock_timeout}. Spring переводит код {@code 55P03} не во всех версиях
     * и конфигурациях, поэтому код проверяется напрямую.
     *
     * @param exception ошибка доступа к данным
     * @return {@code true}, если блокировку не дождались
     */
    static boolean isLockTimeout(final DataAccessException exception) {
        return exception.getMostSpecificCause() instanceof final SQLException sql
                && LOCK_NOT_AVAILABLE.equals(sql.getSQLState());
    }

    /**
     * Идентификатор в кавычках.
     *
     * @param name имя таблицы
     * @return имя, пригодное для подстановки в DDL
     */
    static String quote(final String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * Литерал границы партиции.
     *
     * @param moment граница
     * @return литерал {@code timestamptz} в UTC
     */
    static String literal(final Instant moment) {
        return "'" + moment.atOffset(ZoneOffset.UTC) + "'";
    }

    /**
     * Параметр-момент для JDBC: драйвер PostgreSQL принимает {@link OffsetDateTime}, а не {@link Instant}.
     *
     * @param moment момент
     * @return тот же момент со смещением UTC
     */
    static OffsetDateTime parameter(final Instant moment) {
        return moment.atOffset(ZoneOffset.UTC);
    }
}
