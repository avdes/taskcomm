package xyz.avasilev.taskcomm.task.internal;

import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.internal.ExecutionRepository;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Создание задачи — всегда в транзакции (docs/adr/0094-creation-always-in-transaction.md):
 * внутри транзакции потребителя присоединяется к ней, и задача становится видна воркеру ровно
 * в момент коммита бизнес-данных; вне транзакции открывает свою короткую с явным
 * {@code READ COMMITTED}. Autocommit не используется никогда: дело, задача и блокировка
 * оживления живут в одной транзакции.
 *
 * <p>Транзакция потребителя активна, а {@code DataSource} стартера к ней не привязан
 * (бизнес-данные в другой базе) — создание бросает исключение и своей транзакции не открывает:
 * атомарность с бизнес-данными пропала бы молча. Проверить привязку можно, только если менеджер
 * транзакций держит ресурсы сам; у JTA её не видно, и об этом предупреждают при старте.
 *
 * <p>Время — только {@code now()} базы (docs/adr/0079-database-clock.md): дело получает
 * {@code created_at} через {@code RETURNING}, {@code claim_order_at} считается в том же выражении,
 * что {@code next_attempt_at}.
 */
public class TaskCreator {

    private static final String INSERT_TASK = """
            WITH ready AS (SELECT greatest(coalesce(CAST(:notBefore AS timestamptz), now()), now()) AS at)
            INSERT INTO tc_task (
                id, created_at, updated_at, execution_id, execution_created_at, task_type, payload, status,
                priority, next_attempt_at, claim_order_at, retry_cycle_started_at,
                origin_type, origin_id, origin_attempt_id, origin_relation)
            SELECT :id, now(), now(), :executionId, :executionCreatedAt, :taskType, CAST(:payload AS jsonb), 'PENDING',
                   :priority, ready.at, ready.at + make_interval(secs => :priority * :deadlineStep), now(),
                   :originType, :originId, :originAttemptId, :originRelation
              FROM ready
            RETURNING created_at
            """;

    private final JdbcClient jdbc;

    private final DataSource dataSource;

    private final boolean bindingVisible;

    private final TransactionTemplate transactions;

    private final ExecutionRepository executions;

    private final TaskPartitionHorizon horizon;

    private final UuidV7 ids;

    /**
     * Собирает создание задач на {@code DataSource} и менеджере транзакций стартера.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param executions таблица дел
     * @param ids генератор идентификаторов
     * @param range размер диапазона партиций — для ключа оживления
     */
    public TaskCreator(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final ExecutionRepository executions,
            final UuidV7 ids,
            final PartitionRange range) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dataSource = dataSource;
        this.bindingVisible = transactionManager instanceof ResourceTransactionManager;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.executions = executions;
        this.horizon = new TaskPartitionHorizon(this.jdbc, range);
        this.ids = ids;
    }

    /**
     * Создаёт задачу в статусе {@code PENDING}. Задача в существующем деле оживляет его партицию
     * (docs/adr/0101-active-partition-horizon.md); новое дело всегда в текущем периоде, оживлять
     * там нечего.
     *
     * @param task задача к созданию
     * @return созданная задача
     * @throws TaskcommException если транзакция потребителя активна на другом {@code DataSource}
     */
    CreatedTask create(final NewTask task) {
        this.requireBoundToConsumerTransaction();
        return this.transactions.execute(status -> {
            final ExecutionRef execution;
            if (task.execution() == null) {
                execution = this.executions.create();
            } else {
                execution = task.execution();
                this.horizon.revive(execution.createdAt());
            }
            final UUID id = this.ids.next();
            final Origin origin = task.origin();
            final OffsetDateTime createdAt = this.jdbc
                    .sql(INSERT_TASK)
                    .param("id", id)
                    .param("executionId", execution.id())
                    .param("executionCreatedAt", TaskSql.parameter(execution.createdAt()))
                    .param("taskType", task.taskType())
                    .param("payload", task.payload())
                    .param("priority", task.priority())
                    .param("deadlineStep", TaskSql.seconds(task.deadlineStep()))
                    .param("notBefore", task.notBefore() == null ? null : TaskSql.parameter(task.notBefore()))
                    .param("originType", origin == null ? null : origin.type().name())
                    .param("originId", origin == null ? null : origin.id())
                    .param("originAttemptId", origin == null ? null : origin.attemptId())
                    .param(
                            "originRelation",
                            origin == null ? null : origin.relation().name())
                    .query(OffsetDateTime.class)
                    .single();
            return new CreatedTask(id, execution, createdAt.toInstant());
        });
    }

    private void requireBoundToConsumerTransaction() {
        if (this.bindingVisible
                && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.hasResource(this.dataSource)) {
            throw new TaskcommException("Задача создаётся внутри транзакции, которая не держит DataSource taskcomm: "
                    + "бизнес-данные, по-видимому, в другой базе. Создать задачу атомарно с ними нельзя, а молча "
                    + "открыть отдельную транзакцию значило бы потерять атомарность незаметно. Создавайте задачу "
                    + "вне этой транзакции или пометьте нужный DataSource @TaskcommDataSource "
                    + "(docs/adr/0094-creation-always-in-transaction.md).");
        }
    }
}
