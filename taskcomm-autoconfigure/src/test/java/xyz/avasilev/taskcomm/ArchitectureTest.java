package xyz.avasilev.taskcomm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

/**
 * Проверяет границу публичного API (docs/adr/0004-public-api-boundary.md): пакеты
 * {@code internal} не пересекают границу своей подсистемы, {@code api} не зависит от
 * {@code internal}, а во внутренней реализации нет стереотипных аннотаций Spring
 * (docs/adr/0002-autoconfiguration-registration.md — сканирование в стартере запрещено).
 *
 * <p>Подсистемы с делением {@code api}/{@code internal} — {@code task} и {@code communication} —
 * перечислены явно. Добавление новой такой подсистемы требует дописать сюда ещё одно правило.
 */
@AnalyzeClasses(packages = "xyz.avasilev.taskcomm", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule TASK_INTERNAL_STAYS_WITHIN_TASK_SUBSYSTEM = noClasses()
            .that()
            .resideOutsideOfPackage("xyz.avasilev.taskcomm.task..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("xyz.avasilev.taskcomm.task.internal..");

    @ArchTest
    static final ArchRule COMMUNICATION_INTERNAL_STAYS_WITHIN_COMMUNICATION_SUBSYSTEM = noClasses()
            .that()
            .resideOutsideOfPackage("xyz.avasilev.taskcomm.communication..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("xyz.avasilev.taskcomm.communication.internal..");

    // allowEmptyShould(true) — на этапе 0 в api/internal ещё нет ни одного класса, кроме
    // package-info.java (домен появится на этапе 2). ArchUnit 1.5.0 по умолчанию валит правило,
    // если его `that()` не нашёл ни одного класса, — это защита от опечатки в предикате, а не
    // сигнал, что правило нужно ослаблять, когда классы появятся.
    @ArchTest
    static final ArchRule API_DOES_NOT_DEPEND_ON_INTERNAL = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule INTERNAL_HAS_NO_SPRING_STEREOTYPES = noClasses()
            .that()
            .resideInAPackage("..internal..")
            .should()
            .beAnnotatedWith(Component.class)
            .orShould()
            .beAnnotatedWith(Service.class)
            .orShould()
            .beAnnotatedWith(Repository.class)
            .allowEmptyShould(true);

    private ArchitectureTest() {}
}
