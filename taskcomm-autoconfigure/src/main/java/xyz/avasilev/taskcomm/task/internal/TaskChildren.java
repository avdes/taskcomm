package xyz.avasilev.taskcomm.task.internal;

import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Дети раскрытия: вставка сбросом буфера {@code Emitter}, перевод после закрытия попытки раскрытия
 * и передача родителя в сборку, когда незавершённых детей не осталось
 * (docs/adr/0068-expansion-children-statuses.md, docs/adr/0069-parent-phase-column.md).
 *
 * <p>Каждая операция — своя короткая транзакция: ни миллион строк в одной транзакции, ни строка
 * родителя, заблокированная на всё время работы детей.
 */
class TaskChildren {

    /** Сколько детей переводится одной транзакцией. */
    static final int CHUNK = 1000;

    /**
     * Сброс условен по открытости своей попытки — токену и {@code finished_at IS NULL}. Строка
     * попытки блокируется {@code FOR SHARE} до коммита вставки: переподхват, закрывающий попытку,
     * ждёт его, и перевод детей в {@code ABANDONED} после закрытия видит всех вставленных.
     */
    private static final String LOCK_OPEN_ATTEMPT = """
            SELECT 1 FROM tc_task_attempt
             WHERE execution_created_at = :executionCreatedAt
               AND id = :attemptId
               AND lease_token = :leaseToken
               AND finished_at IS NULL
               FOR SHARE
            """;

    private static final String INSERT_BATCHES = """
            INSERT INTO tc_task_batch (
                id, created_at, updated_at, execution_id, execution_created_at, task_id, parent_attempt_id,
                task_type, payload, status, phase, priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
            SELECT c.id, now(), now(), :executionId, :executionCreatedAt, :taskId, :attemptId,
                   :taskType, CAST(c.payload AS jsonb), 'AWAITING_PARENT', 'EXPAND', :priority, now(),
                   now() + make_interval(secs => :priority * :deadlineStep), now()
              FROM unnest(CAST(:ids AS uuid[]), CAST(:payloads AS text[])) AS c(id, payload)
            """;

    private static final String INSERT_UNITS = """
            INSERT INTO tc_task_unit (
                id, created_at, updated_at, execution_id, execution_created_at, task_id, batch_id, parent_attempt_id,
                task_type, payload, status, priority, next_attempt_at, claim_order_at, retry_cycle_started_at)
            SELECT c.id, now(), now(), :executionId, :executionCreatedAt, :taskId, :batchId, :attemptId,
                   :taskType, CAST(c.payload AS jsonb), 'AWAITING_PARENT', :priority, now(),
                   now() + make_interval(secs => :priority * :deadlineStep), now()
              FROM unnest(CAST(:ids AS uuid[]), CAST(:payloads AS text[])) AS c(id, payload)
            """;

    private static final String SETTLE = """
            UPDATE {table}
               SET status = :status, status_description = :reason, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt
               AND status = 'AWAITING_PARENT'
               AND id IN (SELECT id FROM {table}
                           WHERE execution_created_at = :executionCreatedAt
                             AND parent_attempt_id = :attemptId
                             AND status = 'AWAITING_PARENT'
                           LIMIT :chunk)
            """;

    /**
     * Переход «незавершённых детей не осталось» — условный по статусу родителя и потому
     * идемпотентный. {@code AWAITING_CHILDREN} в списке детей нужен группам, у подзадач его нет.
     */
    private static final String PARENT_READY = """
            UPDATE {parent} p
               SET status = 'PENDING',
                   next_attempt_at = now(),
                   claim_order_at = now() + make_interval(secs => p.priority * :deadlineStep),
                   updated_at = now()
             WHERE p.execution_created_at = :executionCreatedAt
               AND p.id = :parentId
               AND p.status = 'AWAITING_CHILDREN'
               AND NOT EXISTS (SELECT 1 FROM {child} c
                                WHERE c.execution_created_at = :executionCreatedAt
                                  AND c.parent_attempt_id = :attemptId
                                  AND c.status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING', 'AWAITING_CHILDREN'))
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final PollerLayout pollers;

    /**
     * Собирает работу с детьми.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     */
    TaskChildren(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final PollerLayout pollers) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.pollers = pollers;
    }

    /**
     * Вставляет детей одного сброса в {@code AWAITING_PARENT}, если попытка раскрытия ещё открыта.
     * Приоритет наследуется от родителя, {@code claim_order_at} считается сразу
     * (docs/adr/0063-claim-ordering-and-pollers.md).
     *
     * @param attempt попытка раскрытия
     * @param level уровень детей
     * @param ids идентификаторы детей
     * @param payloads нагрузки детей в JSON, в том же порядке
     * @return {@code false}, если попытку уже закрыли и ничего не вставлено
     */
    boolean insert(
            final ClaimedAttempt attempt, final TaskLevel level, final List<UUID> ids, final List<String> payloads) {
        final TaskEntityRef parent = attempt.target();
        final UUID taskId = parent.level() == TaskLevel.TASK
                ? parent.id()
                : attempt.entity().parent().taskId();
        final UUID batchId = parent.level() == TaskLevel.BATCH ? parent.id() : null;
        return Boolean.TRUE.equals(this.transactions.execute(status -> {
            final boolean open = this.jdbc
                    .sql(LOCK_OPEN_ATTEMPT)
                    .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                    .param("attemptId", attempt.lease().attemptId())
                    .param("leaseToken", attempt.lease().token())
                    .query(Integer.class)
                    .optional()
                    .isPresent();
            if (open) {
                this.jdbc
                        .sql(level == TaskLevel.BATCH ? INSERT_BATCHES : INSERT_UNITS)
                        .param("executionId", attempt.entity().executionId())
                        .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                        .param("taskId", taskId)
                        .param("batchId", batchId)
                        .param("attemptId", attempt.lease().attemptId())
                        .param("taskType", attempt.taskType())
                        .param("priority", attempt.entity().priority())
                        .param("deadlineStep", TaskSql.seconds(this.pollers.deadlineStep(attempt.taskType())))
                        .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                        .param("payloads", payloads.toArray(String[]::new))
                        .update();
            }
            return open;
        }));
    }

    /**
     * Переводит детей закрытой попытки раскрытия из {@code AWAITING_PARENT}: после успеха —
     * в {@code PENDING}, после любого другого исхода — в {@code ABANDONED} с причиной. Пачками,
     * каждая своей транзакцией; повторный вызов ничего не меняет.
     *
     * @param parent сущность попытки раскрытия
     * @param attemptId попытка раскрытия
     * @param succeeded попытка закрыта успехом
     */
    void settle(final TaskEntityRef parent, final UUID attemptId, final boolean succeeded) {
        final String status = succeeded ? "PENDING" : "ABANDONED";
        final String reason = succeeded ? null : "попытка раскрытия " + attemptId + " неуспешна";
        for (final TaskLevel level : childLevels(parent.level())) {
            int updated = CHUNK;
            while (updated > 0) {
                updated = this.inTransaction(() -> this.jdbc
                        .sql(SETTLE.replace(TaskRetries.TABLE, level.table()))
                        .param("status", status)
                        .param("reason", reason)
                        .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                        .param("attemptId", attemptId)
                        .param("chunk", CHUNK)
                        .update());
            }
        }
    }

    /**
     * Отдаёт родителя в захват сборки, если у его попытки раскрытия не осталось незавершённых
     * детей. Вызывается после коммита, закрывшего ребёнка, — своей транзакцией: два последних
     * ребёнка, закрытые одновременно, не видят коммитов друг друга внутри своих транзакций, а после
     * коммита последний из них видит всех. Блокировать строку родителя при закрытии каждого
     * ребёнка не нужно (docs/adr/0124-parent-ready-after-child-commit.md).
     *
     * @param child закрытый ребёнок
     * @param link его родство
     * @param taskType тип задачи — шаг дедлайна
     * @return {@code true}, если родитель передан в сборку этим вызовом
     */
    boolean parentReady(final TaskEntityRef child, final ClaimedEntity.Parent link, final String taskType) {
        final TaskEntityRef parent = link.entity(child.executionCreatedAt());
        return this.inTransaction(() -> this.jdbc
                        .sql(PARENT_READY
                                .replace("{parent}", parent.level().table())
                                .replace("{child}", child.level().table()))
                        .param("deadlineStep", TaskSql.seconds(this.pollers.deadlineStep(taskType)))
                        .param("executionCreatedAt", TaskSql.parameter(child.executionCreatedAt()))
                        .param("parentId", parent.id())
                        .param("attemptId", link.attemptId())
                        .update())
                == 1;
    }

    /**
     * Уровни, на которых у сущности бывают дети. У задачи это группы или подзадачи — по форме
     * контракта; спрашиваются обе таблицы, во второй индекс ничего не находит.
     *
     * @param parent уровень родителя
     * @return уровни детей
     */
    static List<TaskLevel> childLevels(final TaskLevel parent) {
        return switch (parent) {
            case TASK -> List.of(TaskLevel.BATCH, TaskLevel.UNIT);
            case BATCH -> List.of(TaskLevel.UNIT);
            case UNIT -> List.of();
        };
    }

    private int inTransaction(final IntSupplier work) {
        final Integer result = this.transactions.execute(status -> work.getAsInt());
        return result == null ? 0 : result;
    }
}
