package xyz.avasilev.taskcomm.schema.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.TestPostgres;

/**
 * Changelog стартера накатывается Liquibase ветки 4 — последней под Apache 2.0, 4.33.0
 * (docs/adr/0092-jackson-3-liquibase-compatibility.md), — и даёт ту же схему, что скрипт.
 *
 * <p>В classpath тестов — Liquibase 5 из BOM, поэтому версия 4 грузится отдельным загрузчиком
 * классов из jar, которые передаёт сборка (конфигурация {@code liquibase4}). Родитель загрузчика —
 * платформенный: ни один класс Liquibase 5 туда не просачивается. Запускается штатная командная
 * строка Liquibase, как её запускал бы потребитель.
 */
class Liquibase4ChangelogIT {

    private static final String CLASSPATH_PROPERTY = "taskcomm.test.liquibase4.classpath";
    private static final String CHANGELOG = "db/changelog/taskcomm/master.xml";
    private static final String LIQUIBASE_VERSION = "4.33.0";

    @Test
    void liquibase4AppliesChangelogAndBuildsTheSameSchemaAsScript() throws Exception {
        final String schema = TestPostgres.newSchema();
        final DataSource script = TestPostgres.dataSource(TestPostgres.newSchema());
        SchemaScriptIT.initialize(script);

        final int exitCode = runLiquibase4(
                "--url=" + TestPostgres.jdbcUrl(schema),
                "--username=" + TestPostgres.username(),
                "--password=" + TestPostgres.password(),
                "--changelog-file=" + CHANGELOG,
                "--show-banner=false",
                "--analytics-enabled=false",
                "update");

        assertThat(exitCode).isZero();
        final DataSource liquibase = TestPostgres.dataSource(schema);
        assertThat(JdbcClient.create(liquibase)
                        .sql("SELECT DISTINCT liquibase FROM databasechangelog")
                        .query(String.class)
                        .list())
                .containsExactly(LIQUIBASE_VERSION);
        assertThat(SchemaScriptIT.catalog(liquibase)).isEqualTo(SchemaScriptIT.catalog(script));
    }

    private static int runLiquibase4(final String... arguments) throws Exception {
        final Thread thread = Thread.currentThread();
        final ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(liquibase4Classpath(), ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            final Class<?> commandLine = loader.loadClass("liquibase.integration.commandline.LiquibaseCommandLine");
            final Object instance = commandLine.getConstructor().newInstance();
            return (int) commandLine.getMethod("execute", String[].class).invoke(instance, (Object) arguments);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Jar Liquibase 4 и его зависимостей, драйвер PostgreSQL и ресурсы стартера с changelog.
     *
     * @return адреса для изолированного загрузчика
     */
    private static URL[] liquibase4Classpath() throws Exception {
        final String classpath = System.getProperty(CLASSPATH_PROPERTY);
        assertThat(classpath)
                .as("системное свойство %s задаёт задача integrationTest", CLASSPATH_PROPERTY)
                .isNotBlank();
        final List<URL> urls = new ArrayList<>();
        for (final String entry : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            urls.add(new File(entry).toURI().toURL());
        }
        urls.add(Class.forName("org.postgresql.Driver")
                .getProtectionDomain()
                .getCodeSource()
                .getLocation());
        final URL changelog = Liquibase4ChangelogIT.class.getClassLoader().getResource(CHANGELOG);
        assertThat(changelog).isNotNull();
        final String changelogUrl = changelog.toString();
        urls.add(URI.create(changelogUrl.substring(0, changelogUrl.length() - CHANGELOG.length()))
                .toURL());
        return urls.toArray(URL[]::new);
    }
}
