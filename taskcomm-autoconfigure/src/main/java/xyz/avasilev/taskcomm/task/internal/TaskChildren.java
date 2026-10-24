package xyz.avasilev.taskcomm.task.internal;

import java.util.List;
import java.util.Optional;
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

    /** Последняя успешная попытка раскрытия сущности — та, чьих детей ждут и собирают. */
    static final String LATEST_EXPANSION = """
            SELECT id FROM tc_task_attempt
             WHERE execution_created_at = :executionCreatedAt
               AND level = :level
               AND target_id = :targetId
               AND phase = 'EXPAND'
               AND status = 'SUCCEEDED'
             ORDER BY started_at DESC
             LIMIT 1
            """;

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
     * идемпотентный. У отменённого родителя или родителя с отменённым предком сборка
     * не запускается: он становится {@code CANCELLED} (docs/adr/0070-cancellation-on-any-entity.md).
     */
    private static final String PARENT_READY = """
            UPDATE {parent} p
               SET status = CASE WHEN {cancelled} THEN 'CANCELLED' ELSE 'PENDING' END,
                   status_description = CASE WHEN {cancelled} THEN coalesce(p.status_description, {reason})
                                             ELSE p.status_description END,
                   next_attempt_at = now(),
                   claim_order_at = now() + make_interval(secs => p.priority * :deadlineStep),
                   updated_at = now()
             WHERE p.execution_created_at = :executionCreatedAt
               AND p.id = :parentId
               AND p.status = 'AWAITING_CHILDREN'
            {noUnfinished}
            RETURNING p.status
            """;

    /** Незавершённые дети попытки раскрытия; {@code AWAITING_CHILDREN} нужен группам. */
    private static final String NO_UNFINISHED = """
               AND NOT EXISTS (SELECT 1 FROM {child} c
                                WHERE c.execution_created_at = :executionCreatedAt
                                  AND c.parent_attempt_id = :attemptId
                                  AND c.status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING', 'AWAITING_CHILDREN'))
            """;

    private static final String BATCH_CANCELLED = """
            (p.cancel_requested_at IS NOT NULL OR EXISTS (SELECT 1 FROM tc_task t
                WHERE t.execution_created_at = p.execution_created_at AND t.id = p.task_id
                  AND t.cancel_requested_at IS NOT NULL))""";

    private static final String BATCH_CANCEL_REASON = "CASE WHEN p.cancel_requested_at IS NOT NULL THEN '"
            + TaskCancelFlags.SELF + "' ELSE 'отменён предок TASK ' || p.task_id END";

    private static final String BATCH_LINK = """
            SELECT task_id, parent_attempt_id FROM tc_task_batch WHERE execution_created_at = :executionCreatedAt AND id = :id
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
     * Переводит детей успешной попытки раскрытия из {@code AWAITING_PARENT} в {@code PENDING}.
     * Пачками, каждая своей транзакцией; повторный вызов ничего не меняет.
     *
     * @param parent сущность попытки раскрытия
     * @param attemptId попытка раскрытия
     */
    void release(final TaskEntityRef parent, final UUID attemptId) {
        this.settle(parent, attemptId, "PENDING", null);
    }

    /**
     * Переводит детей неуспешной попытки раскрытия — любым исходом, кроме успеха, — в
     * {@code ABANDONED} с причиной.
     *
     * @param parent сущность попытки раскрытия
     * @param attemptId попытка раскрытия
     */
    void abandon(final TaskEntityRef parent, final UUID attemptId) {
        this.settle(parent, attemptId, "ABANDONED", "попытка раскрытия " + attemptId + " неуспешна");
    }

    /**
     * Переводит детей успешной попытки раскрытия отменённой сущности сразу в {@code SKIPPED}:
     * работы над ними не начиналось и не начнётся (docs/adr/0070-cancellation-on-any-entity.md).
     *
     * @param parent сущность попытки раскрытия
     * @param attemptId попытка раскрытия
     * @param reason причина — какой предок отменён
     */
    void skip(final TaskEntityRef parent, final UUID attemptId, final String reason) {
        this.settle(parent, attemptId, "SKIPPED", reason);
    }

    /**
     * Отдаёт родителя в захват сборки, если у его попытки раскрытия не осталось незавершённых
     * детей; у отменённого родителя вместо сборки — {@code CANCELLED}. Вызывается после коммита,
     * закрывшего ребёнка, — своей транзакцией: два последних ребёнка, закрытые одновременно,
     * не видят коммитов друг друга внутри своих транзакций, а после коммита последний из них видит
     * всех. Блокировать строку родителя при закрытии каждого ребёнка не нужно
     * (docs/adr/0124-parent-ready-after-child-commit.md).
     *
     * @param child закрытый ребёнок
     * @param link его родство
     * @param taskType тип задачи — шаг дедлайна
     * @return {@code true}, если родитель сменил статус этим вызовом
     */
    boolean parentReady(final TaskEntityRef child, final ClaimedEntity.Parent link, final String taskType) {
        return this.ready(link.entity(child.executionCreatedAt()), link.attemptId(), taskType);
    }

    /**
     * Тот же переход для родителя, у которого неизвестно, какой ребёнок закрылся последним, —
     * по детям его последнего успешного раскрытия. Нужен отмене и подстраховочному поллеру.
     *
     * @param parent родитель
     * @param taskType тип задачи — шаг дедлайна
     * @return {@code true}, если родитель сменил статус этим вызовом
     */
    boolean finishIfDone(final TaskEntityRef parent, final String taskType) {
        return this.latestExpansion(parent)
                .map(attemptId -> this.ready(parent, attemptId, taskType))
                .orElse(false);
    }

    /**
     * Последняя успешная попытка раскрытия сущности.
     *
     * @param parent сущность
     * @return попытка или пусто, если успешного раскрытия не было
     */
    Optional<UUID> latestExpansion(final TaskEntityRef parent) {
        return this.jdbc
                .sql(LATEST_EXPANSION)
                .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                .param("level", parent.level().name())
                .param("targetId", parent.id())
                .query(UUID.class)
                .optional();
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

    private void settle(final TaskEntityRef parent, final UUID attemptId, final String status, final String reason) {
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
     * Переход родителя своей транзакцией. Группа, ставшая {@code CANCELLED}, сама терминальна —
     * и, возможно, была последней незавершённой у задачи: переход проверяется и уровнем выше.
     */
    private boolean ready(final TaskEntityRef parent, final UUID attemptId, final String taskType) {
        final boolean batch = parent.level() == TaskLevel.BATCH;
        final StringBuilder noUnfinished = new StringBuilder();
        childLevels(parent.level())
                .forEach(level -> noUnfinished.append(NO_UNFINISHED.replace("{child}", level.table())));
        final String status = this.transactions.execute(transaction -> this.jdbc
                .sql(PARENT_READY
                        .replace("{parent}", parent.level().table())
                        .replace("{cancelled}", batch ? BATCH_CANCELLED : "p.cancel_requested_at IS NOT NULL")
                        .replace("{reason}", batch ? BATCH_CANCEL_REASON : "'" + TaskCancelFlags.SELF + "'")
                        .replace("{noUnfinished}", noUnfinished.toString().strip()))
                .param("deadlineStep", TaskSql.seconds(this.pollers.deadlineStep(taskType)))
                .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                .param("parentId", parent.id())
                .param("attemptId", attemptId)
                .query(String.class)
                .optional()
                .orElse(null));
        if (batch && "CANCELLED".equals(status)) {
            this.jdbc
                    .sql(BATCH_LINK)
                    .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                    .param("id", parent.id())
                    .query((rs, rowNum) -> new ClaimedEntity.Parent(
                            rs.getObject("task_id", UUID.class), null, rs.getObject("parent_attempt_id", UUID.class)))
                    .optional()
                    .ifPresent(link -> this.parentReady(parent, link, taskType));
        }
        return status != null;
    }

    private int inTransaction(final IntSupplier work) {
        final Integer result = this.transactions.execute(status -> work.getAsInt());
        return result == null ? 0 : result;
    }
}
