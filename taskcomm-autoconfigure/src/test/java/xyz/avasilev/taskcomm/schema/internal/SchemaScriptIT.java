package xyz.avasilev.taskcomm.schema.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TestPostgres;

/**
 * Файл схемы на настоящем PostgreSQL (docs/adr/0085, docs/adr/0109): поднимается с нуля обоими
 * путями — как скрипт {@code taskcomm.schema.initialize} и как Liquibase-changelog, — и оба пути
 * дают одну и ту же схему; у каждой таблицы и колонки есть комментарий; проверка схемы при старте
 * принимает свежую схему и ловит расхождение.
 */
class SchemaScriptIT {

    private static final String CHANGELOG = "db/changelog/taskcomm/master.xml";

    @Test
    void scriptCreatesSchemaFromScratchAndIsRepeatable() {
        final DataSource dataSource = TestPostgres.dataSource(TestPostgres.newSchema());

        initialize(dataSource);
        initialize(dataSource);

        assertThat(columns(dataSource)).isEqualTo(SchemaVerifier.EXPECTED_COLUMNS);
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT count(*) FROM tc_partition_horizon")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void liquibaseChangelogBuildsTheSameSchemaAsScript() throws Exception {
        final DataSource script = TestPostgres.dataSource(TestPostgres.newSchema());
        final DataSource liquibase = TestPostgres.dataSource(TestPostgres.newSchema());

        initialize(script);
        applyChangelog(liquibase);

        assertThat(catalog(liquibase)).isEqualTo(catalog(script));
    }

    @Test
    void everyTableAndColumnHasComment() {
        final DataSource dataSource = TestPostgres.dataSource(TestPostgres.newSchema());
        initialize(dataSource);

        final List<String> uncommented =
                JdbcClient.create(dataSource).sql("""
                        SELECT c.relname || coalesce('.' || a.attname, '')
                          FROM pg_class c
                          LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                         WHERE c.relnamespace = current_schema()::regnamespace
                           AND c.relkind IN ('r', 'p')
                           AND c.relname LIKE 'tc\\_%'
                           AND (obj_description(c.oid, 'pg_class') IS NULL
                                OR col_description(c.oid, a.attnum) IS NULL)
                        """).query(String.class).list();

        assertThat(uncommented).isEmpty();
    }

    @Test
    void verifierAcceptsFreshSchema() {
        final DataSource dataSource = TestPostgres.dataSource(TestPostgres.newSchema());
        initialize(dataSource);

        new SchemaVerifier(JdbcClient.create(dataSource)).verify();
    }

    @Test
    void verifierNamesMissingTablesAndWhatToApply() {
        final String schema = TestPostgres.newSchema();
        final SchemaVerifier verifier = new SchemaVerifier(JdbcClient.create(TestPostgres.dataSource(schema)));

        assertThatThrownBy(verifier::verify)
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("'" + schema + "'")
                .hasMessageContaining("нет таблиц tc_execution, tc_task")
                .hasMessageContaining("Примените миграции taskcomm 2.0.0")
                .hasMessageContaining(CHANGELOG);
    }

    @Test
    void verifierNamesMissingColumnAndUnpartitionedTable() {
        final DataSource dataSource = TestPostgres.dataSource(TestPostgres.newSchema());
        initialize(dataSource);
        final JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("ALTER TABLE tc_task DROP COLUMN phase CASCADE").update();
        jdbc.sql("DROP TABLE tc_task_result").update();
        jdbc.sql("CREATE TABLE tc_task_result (id uuid, created_at timestamptz, updated_at timestamptz,"
                        + " execution_created_at timestamptz, attempt_id uuid, level text, target_id uuid,"
                        + " result jsonb)")
                .update();

        assertThatThrownBy(new SchemaVerifier(jdbc)::verify)
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("в таблице tc_task нет колонок phase")
                .hasMessageContaining("таблица tc_task_result не партиционирована");
    }

    static void initialize(final DataSource dataSource) {
        new TaskcommSchemaInitializer(dataSource, DatabaseInitializationMode.ALWAYS).initializeDatabase();
    }

    private static void applyChangelog(final DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            final Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Scope.child(
                    Scope.Attr.resourceAccessor,
                    new ClassLoaderResourceAccessor(),
                    () -> new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                            .execute());
        }
    }

    private static Map<String, List<String>> columns(final DataSource dataSource) {
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        JdbcClient.create(dataSource)
                .sql("""
                        SELECT c.relname AS table_name, a.attname AS column_name
                          FROM pg_class c
                          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                         WHERE c.relnamespace = current_schema()::regnamespace
                           AND c.relkind IN ('r', 'p')
                           AND c.relname LIKE 'tc\\_%'
                         ORDER BY c.oid, a.attnum
                        """)
                .query((rs, rowNum) -> columns.computeIfAbsent(rs.getString("table_name"), key -> new ArrayList<>())
                        .add(rs.getString("column_name")))
                .list();
        return columns;
    }

    /**
     * Всё, что описывает схему: колонки с типами, умолчаниями и обязательностью, ограничения,
     * индексы, комментарии. Имя схемы из определений вырезано — пути сравниваются в разных схемах.
     */
    static List<String> catalog(final DataSource dataSource) {
        return JdbcClient.create(dataSource).sql("""
                        SELECT 'column ' || c.relname || '.' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
                               || CASE WHEN a.attnotnull THEN ' not null' ELSE '' END
                               || coalesce(' default ' || pg_get_expr(d.adbin, d.adrelid), '')
                               || ' -- ' || coalesce(col_description(c.oid, a.attnum), '')
                          FROM pg_class c
                          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                          LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                         WHERE c.relnamespace = current_schema()::regnamespace
                           AND c.relkind IN ('r', 'p') AND c.relname LIKE 'tc\\_%'
                        UNION ALL
                        SELECT 'table ' || c.relname || ' ' || c.relkind::text || ' -- ' || coalesce(obj_description(c.oid, 'pg_class'), '')
                          FROM pg_class c
                         WHERE c.relnamespace = current_schema()::regnamespace
                           AND c.relkind IN ('r', 'p') AND c.relname LIKE 'tc\\_%'
                        UNION ALL
                        SELECT 'constraint ' || c.relname || ' ' || con.conname || ' ' || pg_get_constraintdef(con.oid)
                          FROM pg_constraint con
                          JOIN pg_class c ON c.oid = con.conrelid
                         WHERE c.relnamespace = current_schema()::regnamespace AND c.relname LIKE 'tc\\_%'
                        UNION ALL
                        SELECT 'index ' || replace(indexdef, current_schema() || '.', '')
                          FROM pg_indexes
                         WHERE schemaname = current_schema() AND tablename LIKE 'tc\\_%'
                        ORDER BY 1
                        """).query(String.class).list();
    }
}
