package xyz.avasilev.taskcomm.task.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.avasilev.taskcomm.task.api.TaskCancelResult;

/**
 * Отмена на любой сущности задач (docs/adr/0070-cancellation-on-any-entity.md,
 * docs/adr/0100-cancel-keeps-real-outcome.md, docs/adr/0125-task-cancel-api.md).
 *
 * <p>Флаг {@code cancel_requested_at} ставится только на отменённой сущности, вниз не копируется.
 * Не начинавшаяся и ждущая сущность ({@code PENDING}, {@code AWAITING_PARENT}) становится
 * {@code CANCELLED} в той же транзакции; работающая доигрывает и пишет настоящий исход; ждущая
 * детей становится {@code CANCELLED}, когда они закончатся. Терминальная сущность не отменяется:
 * флаг на ней навсегда запретил бы перезапуск (docs/adr/0071-no-restart-after-cancel.md).
 *
 * <p>После коммита — перевод потомков пачками, каждая своей транзакцией, условно по статусу:
 * без попыток — {@code SKIPPED}, с попытками — {@code CANCELLED}. Прерванный перевод доводит
 * подстраховочный поллер, а окно до перевода закрывает проверка флагов предков при захвате.
 */
@Slf4j
class TaskCancellation {

    private static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "WARNING", "FAILED", "CANCELLED", "SKIPPED", "ABANDONED");

    private static final String LOCK = """
            SELECT status, cancel_requested_at IS NOT NULL AS cancelled, task_type, {link}
              FROM {table}
             WHERE execution_created_at = :executionCreatedAt AND id = :id
               FOR UPDATE
            """;

    private static final String MARK = """
            UPDATE {table}
               SET cancel_requested_at = now(),
                   status = CASE WHEN status IN ('PENDING', 'AWAITING_PARENT') THEN 'CANCELLED' ELSE status END,
                   status_description = CASE WHEN status IN ('PENDING', 'AWAITING_PARENT') OR :always THEN :reason
                                             ELSE status_description END,
                   updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id
            """;

    private static final String EXPANSIONS = """
            SELECT id FROM tc_task_attempt
             WHERE execution_created_at = :executionCreatedAt AND level = :level AND target_id = :id AND phase = 'EXPAND'
            """;

    /** Не начинавшиеся и ждущие потомки: без попыток — {@code SKIPPED}, с попытками — {@code CANCELLED}. */
    private static final String STOP_CHILDREN = """
            UPDATE {table} c
               SET status = CASE WHEN EXISTS (SELECT 1 FROM tc_task_attempt x
                                               WHERE x.execution_created_at = c.execution_created_at
                                                 AND x.level = :level AND x.target_id = c.id)
                                 THEN 'CANCELLED' ELSE 'SKIPPED' END,
                   status_description = :reason,
                   updated_at = now()
             WHERE c.execution_created_at = :executionCreatedAt
               AND c.status IN ('PENDING', 'AWAITING_PARENT')
               AND c.id IN (SELECT id FROM {table}
                             WHERE execution_created_at = :executionCreatedAt
                               AND parent_attempt_id = :attemptId
                               AND status IN ('PENDING', 'AWAITING_PARENT')
                             LIMIT :chunk)
            """;

    /** Группы, у которых могут быть живые подзадачи. */
    private static final String LIVE_BATCHES = """
            SELECT id, status FROM tc_task_batch
             WHERE execution_created_at = :executionCreatedAt
               AND parent_attempt_id = :attemptId
               AND status IN ('AWAITING_CHILDREN', 'RUNNING')
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final TaskChildren children;

    /**
     * Собирает отмену.
     *
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     * @param children дети раскрытия — передача родителя дальше
     */
    TaskCancellation(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final TaskChildren children) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.children = children;
    }

    /**
     * Отменяет сущность по просьбе человека или кода потребителя.
     *
     * @param target сущность
     * @return принята отмена или отвергнута
     */
    TaskCancelResult cancel(final TaskEntityRef target) {
        final Marked marked = this.transactions.execute(status -> this.mark(target, TaskCancelFlags.SELF, false));
        if (marked == null || marked.rejection() != null) {
            return new TaskCancelResult.Rejected(marked == null ? "отмена не выполнена" : marked.rejection());
        }
        this.afterMark(target, marked);
        return new TaskCancelResult.Accepted(accepted(marked.status()));
    }

    /**
     * Отменяет задачу движком: форма контракта, с которой она начата, не совпадает с формой
     * текущего процессора (docs/adr/0072-contract-shape-check-on-children.md). Причина пишется
     * в задачу сразу и остаётся в ней, когда задача станет {@code CANCELLED}.
     *
     * @param task задача
     * @param reason расхождение форм
     */
    void cancelForShape(final TaskEntityRef task, final String reason) {
        final Marked marked = this.transactions.execute(status -> this.mark(task, reason, true));
        if (marked != null && marked.rejection() == null) {
            log.error(
                    "Задача {} отменена: {}. Контракт живого типа не меняют — заводят новый тип задачи "
                            + "(docs/adr/0028-contract-migration.md).",
                    task.id(),
                    reason);
            this.afterMark(task, marked);
        }
    }

    /**
     * Переводит не начинавшихся и ждущих потомков отменённой сущности и закрывает тех, кому больше
     * нечего ждать. Повторный вызов ничего не меняет.
     *
     * @param by отменённая сущность
     * @param taskType тип задачи
     */
    void descendants(final TaskEntityRef by, final String taskType) {
        final String reason = TaskCancelFlags.ancestor(by);
        this.stopDescendants(by, reason, taskType);
        this.children.finishIfDone(by, taskType);
    }

    private void stopDescendants(final TaskEntityRef parent, final String reason, final String taskType) {
        if (parent.level() == TaskLevel.UNIT) {
            return;
        }
        final List<UUID> expansions = this.jdbc
                .sql(EXPANSIONS)
                .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                .param("level", parent.level().name())
                .param("id", parent.id())
                .query(UUID.class)
                .list();
        for (final UUID attemptId : expansions) {
            for (final TaskLevel level : TaskChildren.childLevels(parent.level())) {
                this.stopChildren(parent, attemptId, level, reason);
            }
            if (parent.level() == TaskLevel.TASK) {
                this.stopBatches(parent, attemptId, reason, taskType);
            }
        }
    }

    private void stopChildren(
            final TaskEntityRef parent, final UUID attemptId, final TaskLevel level, final String reason) {
        int updated = TaskChildren.CHUNK;
        while (updated > 0) {
            final Integer chunk = this.transactions.execute(status -> this.jdbc
                    .sql(STOP_CHILDREN.replace(TaskRetries.TABLE, level.table()))
                    .param("level", level.name())
                    .param("reason", reason)
                    .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                    .param("attemptId", attemptId)
                    .param("chunk", TaskChildren.CHUNK)
                    .update());
            updated = chunk == null ? 0 : chunk;
        }
    }

    /** Подзадачи групп, которые ещё работают или ждут детей, — уровнем ниже, той же причиной. */
    private void stopBatches(
            final TaskEntityRef task, final UUID attemptId, final String reason, final String taskType) {
        final List<LiveBatch> batches = this.jdbc
                .sql(LIVE_BATCHES)
                .param("executionCreatedAt", TaskSql.parameter(task.executionCreatedAt()))
                .param("attemptId", attemptId)
                .query((rs, rowNum) -> new LiveBatch(
                        new TaskEntityRef(TaskLevel.BATCH, rs.getObject("id", UUID.class), task.executionCreatedAt()),
                        rs.getString("status")))
                .list();
        for (final LiveBatch batch : batches) {
            this.stopDescendants(batch.entity(), reason, taskType);
            if ("AWAITING_CHILDREN".equals(batch.status())) {
                this.children.finishIfDone(batch.entity(), taskType);
            }
        }
    }

    private Marked mark(final TaskEntityRef target, final String reason, final boolean always) {
        final Marked found = this.jdbc
                .sql(LOCK.replace(TaskRetries.TABLE, target.level().table()).replace("{link}", link(target.level())))
                .param("executionCreatedAt", TaskSql.parameter(target.executionCreatedAt()))
                .param("id", target.id())
                .query((rs, rowNum) -> new Marked(
                        rs.getString("status"),
                        rs.getString("task_type"),
                        rejection(rs.getString("status"), rs.getBoolean("cancelled")),
                        ClaimRows.parent(rs)))
                .optional()
                .orElse(new Marked(null, null, "не найдена: " + target.level() + " " + target.id(), null));
        if (found.rejection() == null) {
            this.jdbc
                    .sql(MARK.replace(TaskRetries.TABLE, target.level().table()))
                    .param("reason", reason)
                    .param("always", always)
                    .param("executionCreatedAt", TaskSql.parameter(target.executionCreatedAt()))
                    .param("id", target.id())
                    .update();
        }
        return found;
    }

    private void afterMark(final TaskEntityRef target, final Marked marked) {
        try {
            this.descendants(target, marked.taskType());
            if (marked.parent() != null && isWaiting(marked.status())) {
                this.children.parentReady(target, marked.parent(), marked.taskType());
            }
        } catch (final RuntimeException failure) {
            log.warn(
                    "Отмена {} {} принята, но перевод потомков не доведён; его доведёт подстраховочный поллер",
                    target.level(),
                    target.id(),
                    failure);
        }
    }

    private static String rejection(final String status, final boolean cancelled) {
        final String rejection;
        if (cancelled) {
            rejection = "уже отменена";
        } else if (TERMINAL.contains(status)) {
            rejection = "уже в терминальном статусе " + status + ": отменять нечего";
        } else {
            rejection = null;
        }
        return rejection;
    }

    private static String accepted(final String status) {
        final String reason;
        if (isWaiting(status)) {
            reason = "отменена сразу: работа над ней не шла";
        } else if ("RUNNING".equals(status)) {
            reason = "отмена принята: идёт попытка, она доиграет и запишет настоящий исход";
        } else {
            reason = "отмена принята: станет CANCELLED, когда закончатся потомки";
        }
        return reason;
    }

    private static boolean isWaiting(final String status) {
        return "PENDING".equals(status) || "AWAITING_PARENT".equals(status);
    }

    /** Колонки родства в {@link #LOCK}: у задачи их нет. */
    private static String link(final TaskLevel level) {
        return switch (level) {
            case TASK -> "NULL::uuid AS parent_task_id, NULL::uuid AS parent_batch_id, NULL::uuid AS parent_attempt_id";
            case BATCH -> "task_id AS parent_task_id, NULL::uuid AS parent_batch_id, parent_attempt_id";
            case UNIT -> "task_id AS parent_task_id, batch_id AS parent_batch_id, parent_attempt_id";
        };
    }

    /**
     * Что нашлось под блокировкой.
     *
     * @param status статус до отмены
     * @param taskType тип задачи
     * @param rejection почему отмена отвергнута или {@code null}
     * @param parent родство ребёнка раскрытия или {@code null} у задачи
     */
    private record Marked(String status, String taskType, String rejection, ClaimedEntity.Parent parent) {}

    /**
     * Группа с возможно живыми подзадачами.
     *
     * @param entity ключ группы
     * @param status её статус
     */
    private record LiveBatch(TaskEntityRef entity, String status) {}
}
