package xyz.avasilev.taskcomm.task.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.execution.internal.CurrentExecution;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskCancelResult;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.TaskStatus;
import xyz.avasilev.taskcomm.task.api.TaskSubmitOptions;
import xyz.avasilev.taskcomm.task.api.TaskUnit;

/**
 * {@link TaskService} поверх создания задачи и чтения таблиц задач
 * (docs/adr/0111-task-public-api-shape.md).
 *
 * <p>Дело постановки (docs/rules/execution.md): новое — если попросили новое; явно переданное —
 * если передали, и тогда указатель на создателя пишется, только когда это дело обрабатываемой
 * в потоке сущности; иначе дело и создатель из привязки потока; иначе новое дело. Приоритет —
 * из опций, иначе по типу из {@code yaml} или аннотации на классе, иначе по умолчанию.
 *
 * <p>Чтения идут по точному {@code execution_created_at} — одна партиция.
 */
public class DefaultTaskService implements TaskService {

    private static final String SELECT_TASK = """
            SELECT id, execution_id, execution_created_at, task_type, payload::text AS payload, priority, status,
                   status_description, created_at, origin_type, origin_id, origin_attempt_id, origin_relation
              FROM tc_task
            """;

    private static final String IN_EXECUTION = SELECT_TASK + """
             WHERE execution_created_at = :executionCreatedAt AND execution_id = :executionId AND task_type = :taskType
             ORDER BY created_at, id
            """;

    /**
     * Результат последней по {@code started_at} попытки, кроме {@code LEASE_LOST}; последняя
     * неуспешна или ещё идёт — результата нет (docs/adr/0058-task-result-storage.md).
     */
    private static final String CURRENT_RESULT = """
            SELECT r.result::text
              FROM (SELECT a.id
                      FROM tc_task_attempt a
                     WHERE a.execution_created_at = :executionCreatedAt
                       AND a.level = 'TASK'
                       AND a.target_id = :taskId
                       AND (a.status IS NULL OR a.status <> 'LEASE_LOST')
                     ORDER BY a.started_at DESC
                     LIMIT 1) last
              JOIN tc_task_result r ON r.execution_created_at = :executionCreatedAt AND r.attempt_id = last.id
            """;

    private final Supplier<TaskProcessorRegistry> registry;

    private final Supplier<TaskSettings> settings;

    private final TaskCreator creator;

    private final TaskPayloadCodec codec;

    private final JdbcClient jdbc;

    private final Supplier<TaskCancellation> cancellation;

    /**
     * Собирает сервис.
     *
     * <p>Реестр и настройки берутся при первом вызове, а не при сборке: процессор, которому внедрён
     * {@link TaskService}, — обычный способ ставить задачи из процессора, и реестр, собирающий
     * процессоры, иначе замкнулся бы на сервис.
     *
     * @param registry реестр процессоров
     * @param settings итоговые настройки — приоритет и шаг дедлайна по типу
     * @param creator создание задачи
     * @param codec кодек нагрузок и результатов
     * @param dataSource {@code DataSource} стартера — для чтений и отмены
     * @param transactionManager менеджер транзакций стартера — для отмены
     */
    public DefaultTaskService(
            final Supplier<TaskProcessorRegistry> registry,
            final Supplier<TaskSettings> settings,
            final TaskCreator creator,
            final TaskPayloadCodec codec,
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager) {
        this.registry = SingletonSupplier.of(registry);
        this.settings = SingletonSupplier.of(settings);
        this.creator = creator;
        this.codec = codec;
        this.jdbc = JdbcClient.create(dataSource);
        this.cancellation = SingletonSupplier.of(() -> new TaskCancellation(
                dataSource,
                transactionManager,
                new TaskChildren(
                        dataSource, transactionManager, this.settings.get().pollers())));
    }

    @Override
    public TaskCancelResult cancel(final Task<?> task) {
        return this.cancellation
                .get()
                .cancel(new TaskEntityRef(
                        TaskLevel.TASK, task.id(), task.executionRef().createdAt()));
    }

    @Override
    public TaskCancelResult cancel(final TaskBatch<?> batch) {
        return this.cancellation
                .get()
                .cancel(new TaskEntityRef(
                        TaskLevel.BATCH, batch.id(), batch.executionRef().createdAt()));
    }

    @Override
    public TaskCancelResult cancel(final TaskUnit<?> unit) {
        return this.cancellation
                .get()
                .cancel(new TaskEntityRef(
                        TaskLevel.UNIT, unit.id(), unit.executionRef().createdAt()));
    }

    @Override
    public <T> Task<T> submit(final String taskType, final T payload) {
        return this.submit(taskType, payload, TaskSubmitOptions.defaults());
    }

    @Override
    public <T> Task<T> submit(final String taskType, final T payload, final ExecutionRef execution) {
        return this.submit(taskType, payload, TaskSubmitOptions.defaults().inExecution(execution));
    }

    @Override
    public <T> Task<T> submit(final String taskType, final T payload, final TaskSubmitOptions options) {
        return this.submit(this.registry.get().forSubmit(taskType), payload, options);
    }

    @Override
    public <T> Task<T> submit(final Class<? extends TaskProcessor<T>> processor, final T payload) {
        return this.submit(processor, payload, TaskSubmitOptions.defaults());
    }

    @Override
    public <T> Task<T> submit(
            final Class<? extends TaskProcessor<T>> processor, final T payload, final ExecutionRef execution) {
        return this.submit(processor, payload, TaskSubmitOptions.defaults().inExecution(execution));
    }

    @Override
    public <T> Task<T> submit(
            final Class<? extends TaskProcessor<T>> processor, final T payload, final TaskSubmitOptions options) {
        return this.submit(this.registry.get().forSubmit(processor), payload, options);
    }

    @Override
    public List<Task<?>> findInExecution(final ExecutionRef execution, final String taskType) {
        final RegisteredProcessor processor = this.readable(taskType);
        return List.copyOf(this.read(execution, processor));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<Task<T>> findInExecution(
            final ExecutionRef execution, final Class<? extends TaskProcessor<T>> processor) {
        final RegisteredProcessor registered = this.readable(processor);
        return this.read(execution, registered).stream()
                .map(task -> (Task<T>) task)
                .toList();
    }

    @Override
    public Optional<Object> result(final Task<?> task) {
        final RegisteredProcessor processor = this.readable(task.taskType());
        return this.currentResult(task)
                .map(json -> this.codec.read(json, processor.types().taskResult()));
    }

    @Override
    public <RT> Optional<RT> result(final Task<?> task, final Class<RT> resultType) {
        final RegisteredProcessor processor = this.readable(task.taskType());
        final Class<?> declared = processor.types().taskResult().resolve();
        if (declared == null || !ClassUtils.isAssignable(resultType, declared)) {
            throw new TaskcommException("Результат задачи типа '" + task.taskType() + "' — " + declared
                    + ", а запрошен " + resultType.getName() + ".");
        }
        return this.currentResult(task)
                .map(json ->
                        resultType.cast(this.codec.read(json, processor.types().taskResult())));
    }

    private <T> Task<T> submit(final RegisteredProcessor processor, final T payload, final TaskSubmitOptions options) {
        final Placement placement = placement(options);
        final String type = processor.taskType();
        final int priority =
                options.priority().orElseGet(() -> this.settings.get().priority(type));
        final CreatedTask created = this.creator.create(new NewTask(
                type,
                this.codec.write(payload),
                priority,
                this.settings.get().pollers().deadlineStep(type),
                options.notBefore().orElse(null),
                placement.execution(),
                placement.origin()));
        return new TaskView<>(
                new TaskRow(
                        created.id(),
                        created.execution(),
                        type,
                        priority,
                        TaskStatus.PENDING,
                        null,
                        created.createdAt(),
                        placement.origin()),
                payload);
    }

    private static Placement placement(final TaskSubmitOptions options) {
        final Optional<CurrentExecution.Binding> bound = CurrentExecution.get();
        if (options.newExecution()) {
            return new Placement(null, null);
        }
        if (options.executionRef().isPresent()) {
            final ExecutionRef explicit = options.executionRef().get();
            return new Placement(
                    explicit,
                    bound.filter(binding -> binding.execution().equals(explicit))
                            .map(CurrentExecution.Binding::creator)
                            .orElse(null));
        }
        return bound.map(binding -> new Placement(binding.execution(), binding.creator()))
                .orElseGet(() -> new Placement(null, null));
    }

    private RegisteredProcessor readable(final String taskType) {
        return this.registry
                .get()
                .find(taskType)
                .orElseThrow(() -> new TaskcommException("У типа задачи '" + taskType + "' нет процессора: нагрузку "
                        + "и результат не во что разобрать."));
    }

    private RegisteredProcessor readable(final Class<?> processorClass) {
        final Class<?> userClass = ClassUtils.getUserClass(processorClass);
        return this.registry.get().all().stream()
                .filter(processor -> processor.processorClass().equals(userClass))
                .findFirst()
                .orElseThrow(() -> new TaskcommException(
                        "Класс " + processorClass.getName() + " не зарегистрирован процессором задач."));
    }

    private List<Task<Object>> read(final ExecutionRef execution, final RegisteredProcessor processor) {
        return this.jdbc
                .sql(IN_EXECUTION)
                .param("executionCreatedAt", TaskSql.parameter(execution.createdAt()))
                .param("executionId", execution.id())
                .param("taskType", processor.taskType())
                .query((rs, rowNum) -> (Task<Object>) new TaskView<>(
                        row(rs),
                        this.codec.read(
                                rs.getString("payload"), processor.types().task())))
                .list();
    }

    private Optional<String> currentResult(final Task<?> task) {
        return this.jdbc
                .sql(CURRENT_RESULT)
                .param(
                        "executionCreatedAt",
                        TaskSql.parameter(task.executionRef().createdAt()))
                .param("taskId", task.id())
                .query(String.class)
                .optional();
    }

    private static TaskRow row(final ResultSet rs) throws SQLException {
        final String originType = rs.getString("origin_type");
        return new TaskRow(
                rs.getObject("id", UUID.class),
                new ExecutionRef(
                        rs.getObject("execution_id", UUID.class),
                        rs.getObject("execution_created_at", OffsetDateTime.class)
                                .toInstant()),
                rs.getString("task_type"),
                rs.getInt("priority"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("status_description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                originType == null
                        ? null
                        : new Origin(
                                OriginType.valueOf(originType),
                                rs.getObject("origin_id", UUID.class),
                                rs.getObject("origin_attempt_id", UUID.class),
                                OriginRelation.valueOf(rs.getString("origin_relation"))));
    }

    /**
     * Куда ставится задача.
     *
     * @param execution дело или {@code null} — новое
     * @param origin указатель на создателя или {@code null}
     */
    private record Placement(ExecutionRef execution, Origin origin) {}
}
