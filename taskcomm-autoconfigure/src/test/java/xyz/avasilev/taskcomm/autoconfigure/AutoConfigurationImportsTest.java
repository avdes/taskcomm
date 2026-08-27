package xyz.avasilev.taskcomm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

/**
 * Проверяет сам механизм активации стартера (docs/adr/0002-autoconfiguration-registration.md).
 *
 * <p>Остальные тесты автоконфигураций поднимают класс напрямую через {@code AutoConfigurations.of},
 * что файл {@code AutoConfiguration.imports} полностью обходит. Опечатка в имени класса внутри
 * этого файла не сломала бы ни один такой тест, но сломала бы стартер у каждого потребителя —
 * поэтому список читается тем же загрузчиком, которым его читает сам Spring Boot.
 */
class AutoConfigurationImportsTest {

    private static final String OWN_PACKAGE_PREFIX = "xyz.avasilev.taskcomm";

    @Test
    void springDiscoversAutoConfigurationThroughImportsFile() {
        assertThat(loadImportCandidates()).contains(TaskcommAutoConfiguration.class.getName());
    }

    @Test
    void everyOwnCandidateResolvesToRealAutoConfigurationClass() throws ClassNotFoundException {
        final List<String> ownCandidates = loadImportCandidates().stream()
                .filter(candidate -> candidate.startsWith(OWN_PACKAGE_PREFIX))
                .toList();

        assertThat(ownCandidates).isNotEmpty();
        for (final String candidate : ownCandidates) {
            assertThat(Class.forName(candidate)).hasAnnotation(AutoConfiguration.class);
        }
    }

    /**
     * Читает список кандидатов тем же механизмом, которым его читает Spring Boot при старте.
     *
     * @return полный список кандидатов с classpath, включая чужие
     */
    private static List<String> loadImportCandidates() {
        final List<String> candidates = new ArrayList<>();
        ImportCandidates.load(AutoConfiguration.class, AutoConfigurationImportsTest.class.getClassLoader())
                .forEach(candidates::add);
        return candidates;
    }
}
