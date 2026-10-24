package xyz.avasilev.taskcomm.task.api;

import java.util.List;
import java.util.Optional;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Постановка задач и чтение задач дела (docs/adr/0111-task-public-api-shape.md).
 *
 * <p><b>Постановка пишется в текущую транзакцию потребителя</b>: таблица задач работает как outbox,
 * и задача становится видимой воркеру в момент коммита бизнес-данных; откат убирает и её. Вне
 * транзакции — например, из процессора — стартер открывает свою короткую. Транзакция потребителя
 * на другой базе, чем у стартера, — исключение: атомарность пропала бы молча
 * (docs/adr/0017-task-creation-transaction.md, docs/adr/0094-creation-always-in-transaction.md).
 *
 * <p><b>Дело</b>: поставленная из кода потребителя задача заводит новое дело; поставленная внутри
 * процессора — попадает в дело обрабатываемой сущности. Привязка не переживает смену потока:
 * в другом потоке передайте {@link ProcessingContext#executionRef()} явно
 * (docs/adr/0056-execution-ref.md).
 *
 * <p>Тип задачи с процессором, помеченным {@code @Deprecated}, новых задач не принимает. Тип без
 * процессора — исключение до записи.
 */
public interface TaskService {

    /**
     * Ставит задачу.
     *
     * @param taskType тип задачи
     * @param payload параметры задачи
     * @param <T> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     */
    <T> Task<T> submit(String taskType, T payload);

    /**
     * Ставит задачу в указанное дело — из любого потока.
     *
     * @param taskType тип задачи
     * @param payload параметры задачи
     * @param execution дело; побеждает неявную привязку потока
     * @param <T> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     */
    <T> Task<T> submit(String taskType, T payload, ExecutionRef execution);

    /**
     * Ставит задачу с необязательными параметрами.
     *
     * @param taskType тип задачи
     * @param payload параметры задачи
     * @param options дело, «не раньше», приоритет
     * @param <T> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     */
    <T> Task<T> submit(String taskType, T payload, TaskSubmitOptions options);

    /**
     * Ставит задачу процессору, указанному классом: компилятор сверяет тип нагрузки с дженериком
     * процессора. Тип задачи берётся из реестра процессоров.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload параметры задачи
     * @param <T> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <T> Task<T> submit(Class<? extends TaskProcessor<T>> processor, T payload);

    /**
     * Ставит задачу процессору, указанному классом, в указанное дело.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload параметры задачи
     * @param execution дело; побеждает неявную привязку потока
     * @param <T> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <T> Task<T> submit(Class<? extends TaskProcessor<T>> processor, T payload, ExecutionRef execution);

    /**
     * Ставит задачу процессору, указанному классом, с необязательными параметрами.
     *
     * @param processor класс процессора — бина в контексте
     * @param payload параметры задачи
     * @param options дело, «не раньше», приоритет
     * @param <T> параметры задачи
     * @return созданная задача в статусе {@code PENDING}
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <T> Task<T> submit(Class<? extends TaskProcessor<T>> processor, T payload, TaskSubmitOptions options);

    /**
     * Задачи дела указанного типа, в порядке создания (docs/adr/0110-execution-composition-reads-in-subsystems.md).
     * Чтение идёт в партиции дела. Из {@code process} подзадачи не вызывайте: миллион подзадач дадут
     * миллион запросов; в {@code collect} это один вызов.
     *
     * @param execution дело
     * @param taskType тип задачи
     * @return задачи дела этого типа; нагрузка восстановлена по дженерику процессора
     */
    List<Task<?>> findInExecution(ExecutionRef execution, String taskType);

    /**
     * Задачи дела процессора, указанного классом, в порядке создания.
     *
     * @param execution дело
     * @param processor класс процессора — бина в контексте
     * @param <T> параметры задачи
     * @return задачи дела этого типа
     * @throws IllegalArgumentException если такого процессора нет в реестре
     */
    <T> List<Task<T>> findInExecution(ExecutionRef execution, Class<? extends TaskProcessor<T>> processor);

    /**
     * Текущий результат задачи — результат её последней попытки, если та успешна
     * (docs/adr/0058-task-result-storage.md).
     *
     * @param task задача
     * @return результат, восстановленный по дженерику процессора, или пусто — результата нет,
     *     последняя попытка неуспешна или процессор возвращает {@link Void}
     */
    Optional<Object> result(Task<?> task);

    /**
     * Текущий результат задачи указанного класса.
     *
     * @param task задача
     * @param resultType класс результата задачи — тот, что стоит в дженерике процессора
     * @param <RT> результат задачи
     * @return результат или пусто
     * @throws IllegalArgumentException если класс не совпадает с результатом процессора
     */
    <RT> Optional<RT> result(Task<?> task, Class<RT> resultType);
}
