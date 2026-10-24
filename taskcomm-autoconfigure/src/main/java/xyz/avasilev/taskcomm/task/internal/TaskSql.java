package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Мелкие приёмы SQL подсистемы задач. Похожие есть у кода схемы, но общего кода у подсистемы
 * со схемой нет (docs/adr/0107-schema-internal-package.md).
 */
final class TaskSql {

    private TaskSql() {}

    /**
     * Параметр-момент для JDBC: драйвер PostgreSQL принимает {@link OffsetDateTime}, а не {@link Instant}.
     * {@link Instant#MIN} — нерассчитанная граница активных партиций — уходит как {@code -infinity}:
     * драйвер передаёт так {@link OffsetDateTime#MIN}.
     *
     * @param moment момент
     * @return тот же момент со смещением UTC
     */
    static OffsetDateTime parameter(final Instant moment) {
        return Instant.MIN.equals(moment) ? OffsetDateTime.MIN : moment.atOffset(ZoneOffset.UTC);
    }

    /**
     * Длительность параметром для {@code make_interval(secs => ...)}: приложение передаёт в SQL
     * длительности, а не моменты (docs/adr/0079-database-clock.md).
     *
     * @param duration длительность
     * @return секунды с долями
     */
    static double seconds(final Duration duration) {
        return duration.toNanos() / 1e9;
    }

    /**
     * Шаблон своих коротких транзакций стартера: всегда новая транзакция и явный
     * {@code READ COMMITTED}, без опоры на умолчание базы (docs/adr/0095-dedup-requires-read-committed.md).
     *
     * @param transactionManager менеджер транзакций стартера
     * @return шаблон транзакций
     */
    static TransactionTemplate transactions(final PlatformTransactionManager transactionManager) {
        final TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transactions;
    }

    /**
     * Ставит {@code plan_cache_mode = force_custom_plan} только на текущую транзакцию: граница
     * активных партиций становится известна планировщику, и партиции ниже неё не открываются
     * и не блокируются (docs/adr/0101-active-partition-horizon.md). Сессию соединения из пула
     * потребителя трогать нельзя.
     *
     * @param jdbc клиент в открытой транзакции
     */
    static void forceCustomPlan(final JdbcClient jdbc) {
        jdbc.sql("SELECT set_config('plan_cache_mode', 'force_custom_plan', true)")
                .query(String.class)
                .single();
    }
}
