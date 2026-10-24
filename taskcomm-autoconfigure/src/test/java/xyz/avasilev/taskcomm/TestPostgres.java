package xyz.avasilev.taskcomm;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Один PostgreSQL в Testcontainers на весь прогон интеграционных тестов (docs/adr/0085). Каждый
 * тест получает свою пустую схему: таблицы стартера ложатся в схему соединения, и тесты
 * не видят друг друга, не пересоздавая контейнер.
 */
public final class TestPostgres {

    private static final PostgreSQLContainer CONTAINER = start();
    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    private TestPostgres() {}

    /**
     * Заводит новую пустую схему.
     *
     * @return имя схемы
     */
    public static String newSchema() {
        final String schema = "it_" + SCHEMAS.incrementAndGet();
        JdbcClient.create(dataSource("public")).sql("CREATE SCHEMA " + schema).update();
        return schema;
    }

    /**
     * {@code DataSource}, чьи соединения работают в заданной схеме.
     *
     * @param schema имя схемы
     * @return {@code DataSource} без пула
     */
    public static DataSource dataSource(final String schema) {
        return new DriverManagerDataSource(jdbcUrl(schema), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /**
     * Адрес базы, соединения по которому работают в заданной схеме.
     *
     * @param schema имя схемы
     * @return JDBC URL
     */
    public static String jdbcUrl(final String schema) {
        final String url = CONTAINER.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    /**
     * Пользователь базы.
     *
     * @return имя пользователя
     */
    public static String username() {
        return CONTAINER.getUsername();
    }

    /**
     * Пароль пользователя базы.
     *
     * @return пароль
     */
    public static String password() {
        return CONTAINER.getPassword();
    }

    private static PostgreSQLContainer start() {
        final PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        return container;
    }
}
