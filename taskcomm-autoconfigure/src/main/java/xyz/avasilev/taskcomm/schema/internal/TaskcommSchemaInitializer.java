package xyz.avasilev.taskcomm.schema.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;

/**
 * {@code taskcomm.schema.initialize}: накатывает файлы схемы стартера тем же механизмом, что
 * {@code spring.sql.init}, — только для тестов и локальной разработки
 * (docs/adr/0085-tests-on-real-postgres.md). Файлы — те же, что подключает Liquibase-changelog
 * (docs/adr/0109-schema-as-formatted-sql.md); разметка Liquibase в них — SQL-комментарии.
 *
 * <p>Свой подкласс, а не бин {@code DataSourceScriptDatabaseInitializer}: автоконфигурация
 * {@code spring.sql.init} отступает только перед своим типом, и наш бин её не вытесняет
 * (docs/adr/0096-no-foreign-type-beans.md).
 */
public class TaskcommSchemaInitializer extends DataSourceScriptDatabaseInitializer {

    /** Файлы схемы по порядку версий. Новая версия схемы добавляет файл в конец. */
    static final List<String> SCHEMA_LOCATIONS = List.of("classpath:db/changelog/taskcomm/taskcomm-2.0.0.sql");

    /**
     * Создаёт инициализатор.
     *
     * @param dataSource {@code DataSource} стартера
     * @param mode значение {@code taskcomm.schema.initialize}
     */
    public TaskcommSchemaInitializer(final DataSource dataSource, final DatabaseInitializationMode mode) {
        super(dataSource, settings(mode));
    }

    private static DatabaseInitializationSettings settings(final DatabaseInitializationMode mode) {
        final DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        settings.setSchemaLocations(SCHEMA_LOCATIONS);
        settings.setMode(mode);
        settings.setEncoding(StandardCharsets.UTF_8);
        settings.setContinueOnError(false);
        return settings;
    }
}
