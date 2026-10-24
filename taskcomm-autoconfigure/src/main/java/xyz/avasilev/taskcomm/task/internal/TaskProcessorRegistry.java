package xyz.avasilev.taskcomm.task.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;

/**
 * Реестр процессоров задач, построенный при старте из всех бинов {@link TaskProcessor}
 * (docs/rules/tasks.md). Считается исчерпывающим описанием типов задач в схеме: одна схема —
 * одно приложение (docs/adr/0027-schema-ownership.md).
 *
 * <p>Два процессора одного типа — падение при старте, а не при первой задаче. Устаревшие типы —
 * процессоры с {@code @Deprecated} на классе — громко печатаются: их задачи доделываются, а новые
 * не ставятся (docs/adr/0028-contract-migration.md).
 */
@Slf4j
public final class TaskProcessorRegistry {

    private final Map<String, RegisteredProcessor> byType;

    private final Map<Class<?>, RegisteredProcessor> byClass;

    /**
     * Строит реестр.
     *
     * @param processors все процессоры задач приложения
     * @throws TaskcommConfigurationException если тип назван двумя процессорами или параметр типа
     *     процессора не выводится из класса
     */
    public TaskProcessorRegistry(final Collection<? extends TaskProcessor<?>> processors) {
        final Map<String, RegisteredProcessor> types = new LinkedHashMap<>();
        final Map<Class<?>, RegisteredProcessor> classes = new LinkedHashMap<>();
        for (final TaskProcessor<?> processor : processors) {
            final RegisteredProcessor registered = register(processor);
            final RegisteredProcessor previous = types.putIfAbsent(registered.taskType(), registered);
            if (previous != null) {
                throw new TaskcommConfigurationException("Тип задачи '" + registered.taskType() + "' объявлен двумя "
                        + "процессорами: " + previous.processorClass().getName() + " и "
                        + registered.processorClass().getName() + ". У типа ровно один процессор.");
            }
            classes.put(registered.processorClass(), registered);
        }
        this.byType = Map.copyOf(types);
        this.byClass = Map.copyOf(classes);
        warnAboutDeprecated(types.values());
    }

    /**
     * Процессор типа.
     *
     * @param taskType тип задачи
     * @return процессор или пусто, если типа в реестре нет — задачи такого типа осиротели
     */
    Optional<RegisteredProcessor> find(final String taskType) {
        return Optional.ofNullable(this.byType.get(taskType));
    }

    /**
     * Процессор для постановки по типу: тип обязан быть в реестре и не быть устаревшим.
     *
     * @param taskType тип задачи
     * @return процессор
     * @throws TaskcommException если процессора нет или он помечен {@code @Deprecated}
     */
    RegisteredProcessor forSubmit(final String taskType) {
        final RegisteredProcessor processor = this.find(taskType)
                .orElseThrow(() -> new TaskcommException("Тип задачи '" + taskType + "' не объявлен ни одним "
                        + "процессором. Проверьте имя типа: задачу без процессора некому выполнить."));
        return requireNotDeprecated(processor);
    }

    /**
     * Процессор для постановки по классу.
     *
     * @param processorClass класс процессора
     * @return процессор
     * @throws TaskcommException если класса нет в реестре или процессор помечен {@code @Deprecated}
     */
    RegisteredProcessor forSubmit(final Class<?> processorClass) {
        final RegisteredProcessor processor = this.byClass.get(ClassUtils.getUserClass(processorClass));
        if (processor == null) {
            throw new TaskcommException("Класс " + processorClass.getName() + " не зарегистрирован процессором "
                    + "задач: объявите его бином Spring.");
        }
        return requireNotDeprecated(processor);
    }

    /**
     * Все процессоры в порядке регистрации.
     *
     * @return процессоры
     */
    List<RegisteredProcessor> all() {
        return List.copyOf(this.byType.values());
    }

    /**
     * Известные типы с формами контрактов — то, что поллеры вправе захватывать.
     *
     * @return тип задачи → форма контракта
     */
    Map<String, ContractShape> shapes() {
        return this.byType.values().stream()
                .collect(Collectors.toUnmodifiableMap(RegisteredProcessor::taskType, RegisteredProcessor::shape));
    }

    /**
     * Известные типы.
     *
     * @return типы задач
     */
    Set<String> types() {
        return this.byType.keySet();
    }

    private static RegisteredProcessor register(final TaskProcessor<?> processor) {
        final String taskType = processor.taskType();
        if (taskType == null || taskType.isBlank()) {
            throw new TaskcommConfigurationException(
                    "Процессор " + processor.getClass().getName() + " вернул пустой taskType().");
        }
        final Class<?> processorClass = AopUtils.getTargetClass(processor);
        final ContractShape shape = ContractShape.of(processor);
        return new RegisteredProcessor(
                taskType,
                processor,
                processorClass,
                shape,
                ProcessorTypes.of(processorClass, shape, taskType),
                processorClass.isAnnotationPresent(Deprecated.class));
    }

    private static RegisteredProcessor requireNotDeprecated(final RegisteredProcessor processor) {
        if (processor.deprecated()) {
            throw new TaskcommException("Тип задачи '" + processor.taskType() + "' устарел: процессор "
                    + processor.processorClass().getName() + " помечен @Deprecated, и новые задачи этого типа "
                    + "не ставятся. Существующие доделываются (docs/adr/0028-contract-migration.md).");
        }
        return processor;
    }

    private static void warnAboutDeprecated(final Collection<RegisteredProcessor> processors) {
        final List<String> deprecated = processors.stream()
                .filter(RegisteredProcessor::deprecated)
                .map(RegisteredProcessor::taskType)
                .toList();
        if (!deprecated.isEmpty()) {
            log.warn(
                    "Устаревшие типы задач: {}. Их процессоры помечены @Deprecated: существующие задачи "
                            + "доделываются, постановка новых отвергается исключением. Процессор не удаляется "
                            + "никогда — без него задачи этих типов некому доделать и перезапустить "
                            + "(docs/adr/0029-processor-never-deleted.md).",
                    deprecated);
        }
    }
}
