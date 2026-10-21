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
 * Проверяет границы пакетов (docs/adr/0004-public-api-boundary.md,
 * docs/adr/0087-architecture-rules.md).
 *
 * <ul>
 *   <li>{@code internal} подсистемы используется только внутри неё и из {@code autoconfigure},
 *       где бины собираются вручную (docs/adr/0002-autoconfiguration-registration.md);
 *   <li>{@code execution.internal} вдобавок доступен внутренним пакетам обоих движков — ради
 *       общего держателя текущего дела (docs/adr/0056-execution-ref.md); это единственное
 *       исключение;
 *   <li>{@code api} не зависит от {@code internal};
 *   <li>задачи и коммуникации не зависят друг от друга (docs/adr/0010-separate-engines.md);
 *   <li>{@code execution} не зависит ни от задач, ни от коммуникаций — агрегат пассивен
 *       (docs/adr/0038-execution-aggregate.md);
 *   <li>{@code rest} зависит только от {@code api};
 *   <li>во внутренней реализации нет стереотипных аннотаций Spring — сканирование в стартере
 *       запрещено.
 * </ul>
 *
 * <p>Подсистемы перечислены явно. Добавление новой подсистемы требует дописать сюда правила.
 *
 * <p>{@code allowEmptyShould(true)} стоит там, где в {@code that()} пока нет ни одного класса:
 * ArchUnit по умолчанию валит такое правило — это защита от опечатки в предикате, а не сигнал,
 * что правило нужно ослаблять, когда классы появятся.
 */
@AnalyzeClasses(packages = "xyz.avasilev.taskcomm", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String TASK = "xyz.avasilev.taskcomm.task..";
    static final String TASK_INTERNAL = "xyz.avasilev.taskcomm.task.internal..";
    static final String COMMUNICATION = "xyz.avasilev.taskcomm.communication..";
    static final String COMMUNICATION_INTERNAL = "xyz.avasilev.taskcomm.communication.internal..";
    static final String EXECUTION = "xyz.avasilev.taskcomm.execution..";
    static final String EXECUTION_INTERNAL = "xyz.avasilev.taskcomm.execution.internal..";
    static final String REST = "xyz.avasilev.taskcomm.rest..";
    static final String AUTOCONFIGURE = "xyz.avasilev.taskcomm.autoconfigure..";

    @ArchTest
    static final ArchRule TASK_INTERNAL_STAYS_WITHIN_TASK_SUBSYSTEM = noClasses()
            .that()
            .resideOutsideOfPackages(TASK, AUTOCONFIGURE)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(TASK_INTERNAL)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule COMMUNICATION_INTERNAL_STAYS_WITHIN_COMMUNICATION_SUBSYSTEM = noClasses()
            .that()
            .resideOutsideOfPackages(COMMUNICATION, AUTOCONFIGURE)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(COMMUNICATION_INTERNAL)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule EXECUTION_INTERNAL_SHARED_ONLY_WITH_ENGINE_INTERNALS = noClasses()
            .that()
            .resideOutsideOfPackages(EXECUTION, AUTOCONFIGURE, TASK_INTERNAL, COMMUNICATION_INTERNAL)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(EXECUTION_INTERNAL)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule API_DOES_NOT_DEPEND_ON_INTERNAL = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule TASK_DOES_NOT_DEPEND_ON_COMMUNICATION = noClasses()
            .that()
            .resideInAPackage(TASK)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(COMMUNICATION)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule COMMUNICATION_DOES_NOT_DEPEND_ON_TASK = noClasses()
            .that()
            .resideInAPackage(COMMUNICATION)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(TASK)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule EXECUTION_IS_PASSIVE = noClasses()
            .that()
            .resideInAPackage(EXECUTION)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(TASK, COMMUNICATION)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule REST_USES_ONLY_API = noClasses()
            .that()
            .resideInAPackage(REST)
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
