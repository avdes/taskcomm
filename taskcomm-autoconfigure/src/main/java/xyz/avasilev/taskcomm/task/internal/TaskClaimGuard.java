package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Всё, что такт захвата делает кроме открытия попыток: закрытие брошенных попыток
 * (docs/adr/0067-expired-lease-closes-attempt.md) и проверка кандидатов до открытия попытки (docs/adr/0070-cancellation-on-any-entity.md,
 * docs/adr/0072-contract-shape-check-on-children.md, docs/adr/0026-contract-shape-marking.md).
 *
 * <ul>
 *   <li><b>Ребёнок с отменённым предком</b> закрывается сразу, без попытки и без вызова
 *       процессора: без попыток — {@code SKIPPED}, с попытками — {@code CANCELLED}. Флаги предков
 *       читаются неблокирующим чтением одним запросом на всю пачку — строку общего родителя
 *       блокировать нельзя, читать можно.
 *   <li><b>Форма контракта задачи</b> сверяется с формой текущего процессора в том же чтении.
 *       Несовпадение отменяет задачу механизмом отмены — после коммита такта, а сам ребёнок
 *       закрывается как ребёнок отменённой задачи.
 *   <li><b>Задача, начатая по другой форме</b>, уже заблокирована тактом: она отменяется в той же
 *       транзакции, её потомки — после коммита.
 * </ul>
 */
@Slf4j
class TaskClaimGuard {

    private static final String ANCESTORS = """
            SELECT x.id, t.cancel_requested_at IS NOT NULL AS task_cancelled, t.contract_shape,
                   b.cancel_requested_at IS NOT NULL AS batch_cancelled
              FROM unnest(CAST(:executionCreatedAt AS timestamptz[]), CAST(:id AS uuid[]),
                          CAST(:taskId AS uuid[]), CAST(:batchId AS uuid[]))
                   AS x(execution_created_at, id, task_id, batch_id)
              JOIN tc_task t ON t.execution_created_at >= :activeFrom
                   AND t.execution_created_at = x.execution_created_at AND t.id = x.task_id
              LEFT JOIN tc_task_batch b ON b.execution_created_at >= :activeFrom
                   AND b.execution_created_at = x.execution_created_at AND b.id = x.batch_id
            """;

    /** Ребёнок, захвата которого не будет: без попыток — {@code SKIPPED}, с попытками — {@code CANCELLED}. */
    private static final String STOP_CHILD = """
            UPDATE {table} c
               SET status = CASE WHEN EXISTS (SELECT 1 FROM tc_task_attempt x
                                               WHERE x.execution_created_at = c.execution_created_at
                                                 AND x.level = :level AND x.target_id = c.id)
                                 THEN 'CANCELLED' ELSE 'SKIPPED' END,
                   status_description = :reason,
                   updated_at = now()
             WHERE c.execution_created_at = :executionCreatedAt AND c.id = :id AND c.status = 'PENDING'
            """;

    private static final String STOP_TASK = """
            UPDATE tc_task
               SET cancel_requested_at = now(), status = 'CANCELLED', status_description = :reason, updated_at = now()
             WHERE execution_created_at = :executionCreatedAt AND id = :id AND status = 'PENDING'
            """;

    private final TaskChildren children;

    private final TaskCancellation cancellation;

    private final TaskLeaseExpiry expiry;

    /**
     * Собирает проверку.
     *
     * @param dataSource {@code DataSource} стартера — для доделки после такта
     * @param transactionManager менеджер транзакций стартера
     * @param settings настройки попыток — повторы после брошенной попытки
     * @param pollers раскладка поллеров — шаг дедлайна по типу
     * @param random источник случайных чисел для джиттера
     */
    TaskClaimGuard(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final AttemptSettingsSource settings,
            final PollerLayout pollers,
            final DoubleSupplier random) {
        this.expiry = new TaskLeaseExpiry(dataSource, transactionManager, settings, pollers, random);
        this.children = new TaskChildren(dataSource, transactionManager, pollers);
        this.cancellation = new TaskCancellation(dataSource, transactionManager, this.children);
    }

    /**
     * Проверяет кандидатов в транзакции такта, после локирующего {@code SELECT}, и закрывает тех,
     * кого захватывать нельзя.
     *
     * @param jdbc клиент в транзакции такта
     * @param activeFrom граница активных партиций
     * @param expired брошенные попытки такта — закрываются исходом {@code LEASE_LOST}
     * @param candidates кандидаты такта
     * @param types формы контрактов текущих процессоров
     * @param stopped сюда добавляются кандидаты, которых захватывать нельзя
     * @return доделка после коммита такта
     */
    Runnable check(
            final JdbcClient jdbc,
            final Instant activeFrom,
            final List<ExpiredAttempt> expired,
            final List<ClaimCandidate> candidates,
            final Map<String, ContractShape> types,
            final Set<TaskEntityRef> stopped) {
        final List<ExpiredAttempt> closed = expired.stream()
                .filter(attempt -> this.expiry.close(jdbc, attempt))
                .toList();
        final Verdict verdict = new Verdict(stopped, new ArrayList<>(), new LinkedHashMap<>(), new ArrayList<>());
        candidates.stream()
                .filter(candidate -> candidate.entity().level() == TaskLevel.TASK && candidate.shape() != null)
                .filter(candidate -> candidate.shape() != types.get(candidate.taskType()))
                .forEach(task -> this.stopTask(jdbc, task, types.get(task.taskType()), verdict));
        final List<ClaimCandidate> children = candidates.stream()
                .filter(candidate -> candidate.parent() != null)
                .toList();
        if (!children.isEmpty()) {
            this.checkChildren(jdbc, activeFrom, children, types, verdict);
        }
        return () -> {
            closed.forEach(this.expiry::cascade);
            this.afterCommit(verdict);
        };
    }

    /**
     * Доделывает после коммита такта: потомки отменённых задач, отмена задач с чужой формой,
     * переход родителей закрытых детей. Ошибка не мешает захвату — доведёт подстраховочный поллер.
     *
     * @param verdict итог проверки
     */
    private void afterCommit(final Verdict verdict) {
        try {
            verdict.cancelledTasks().forEach(task -> this.cancellation.descendants(task.entity(), task.taskType()));
            verdict.shapeMismatches()
                    .forEach((task, mismatch) -> this.cancellation.cancelForShape(task, mismatch.reason()));
            verdict.closedChildren()
                    .forEach(child -> this.children.parentReady(child.entity(), child.parent(), child.taskType()));
        } catch (final RuntimeException failure) {
            log.warn("Кандидаты захвата закрыты, но доделка после такта не доведена", failure);
        }
    }

    private void stopTask(
            final JdbcClient jdbc, final ClaimCandidate task, final ContractShape current, final Verdict verdict) {
        final String reason = mismatch(task.shape(), current);
        jdbc.sql(STOP_TASK)
                .param("reason", reason)
                .param("executionCreatedAt", TaskSql.parameter(task.entity().executionCreatedAt()))
                .param("id", task.entity().id())
                .update();
        log.error(
                "Задача {} отменена: {}. Контракт живого типа не меняют — заводят новый тип задачи "
                        + "(docs/adr/0028-contract-migration.md).",
                task.entity().id(),
                reason);
        verdict.stopped().add(task.entity());
        verdict.cancelledTasks().add(task);
    }

    private void checkChildren(
            final JdbcClient jdbc,
            final Instant activeFrom,
            final List<ClaimCandidate> children,
            final Map<String, ContractShape> types,
            final Verdict verdict) {
        final Map<UUID, Ancestors> ancestors = new LinkedHashMap<>();
        jdbc.sql(ANCESTORS)
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param(
                        "executionCreatedAt",
                        TaskSql.column(
                                children, c -> TaskSql.parameter(c.entity().executionCreatedAt())))
                .param("id", TaskSql.column(children, c -> c.entity().id()))
                .param("taskId", TaskSql.column(children, c -> c.parent().taskId()))
                .param("batchId", TaskSql.column(children, c -> c.parent().batchId()))
                .query((rs, rowNum) -> new Ancestors(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("task_cancelled"),
                        rs.getBoolean("batch_cancelled"),
                        rs.getString("contract_shape")))
                .list()
                .forEach(found -> ancestors.put(found.id(), found));
        for (final ClaimCandidate child : children) {
            final Ancestors found = ancestors.get(child.entity().id());
            if (found != null) {
                this.checkChild(jdbc, child, found, types.get(child.taskType()), verdict);
            }
        }
    }

    private void checkChild(
            final JdbcClient jdbc,
            final ClaimCandidate child,
            final Ancestors found,
            final ContractShape current,
            final Verdict verdict) {
        final Instant partition = child.entity().executionCreatedAt();
        final TaskEntityRef task =
                new TaskEntityRef(TaskLevel.TASK, child.parent().taskId(), partition);
        final TaskEntityRef by;
        if (found.batchCancelled()) {
            by = new TaskEntityRef(TaskLevel.BATCH, child.parent().batchId(), partition);
        } else if (found.taskCancelled()) {
            by = task;
        } else if (found.shape() != null && ContractShape.valueOf(found.shape()) != current) {
            verdict.shapeMismatches()
                    .putIfAbsent(task, new Mismatch(mismatch(ContractShape.valueOf(found.shape()), current)));
            by = task;
        } else {
            by = null;
        }
        if (by != null) {
            jdbc.sql(STOP_CHILD.replace(
                            TaskRetries.TABLE, child.entity().level().table()))
                    .param("level", child.entity().level().name())
                    .param("reason", TaskCancelFlags.ancestor(by))
                    .param("executionCreatedAt", TaskSql.parameter(partition))
                    .param("id", child.entity().id())
                    .update();
            verdict.stopped().add(child.entity());
            verdict.closedChildren().add(child);
        }
    }

    private static String mismatch(final ContractShape stored, final ContractShape current) {
        return "форма контракта " + stored + " не совпадает с текущей " + current;
    }

    /**
     * Кандидат захвата — то, что о нём нужно проверке.
     *
     * @param entity ключ строки
     * @param taskType тип задачи
     * @param shape записанная форма контракта задачи; у детей и у не захватывавшейся задачи пусто
     * @param parent родство ребёнка раскрытия; у задачи пусто
     */
    record ClaimCandidate(TaskEntityRef entity, String taskType, ContractShape shape, ClaimedEntity.Parent parent) {}

    /**
     * Итог проверки такта.
     *
     * @param stopped кого не захватывать
     * @param closedChildren закрытые дети — их родителям проверить переход после коммита
     * @param shapeMismatches задачи с чужой формой, найденные через детей, — отменить после коммита
     * @param cancelledTasks задачи с чужой формой, отменённые в такте, — перевести их потомков
     */
    record Verdict(
            Set<TaskEntityRef> stopped,
            List<ClaimCandidate> closedChildren,
            Map<TaskEntityRef, Mismatch> shapeMismatches,
            List<ClaimCandidate> cancelledTasks) {}

    /**
     * Расхождение форм контракта.
     *
     * @param reason причина отмены
     */
    record Mismatch(String reason) {}

    /**
     * Флаги и форма предков ребёнка.
     *
     * @param id ребёнок
     * @param taskCancelled на задаче стоит отметка отмены
     * @param batchCancelled на группе стоит отметка отмены
     * @param shape записанная форма контракта задачи
     */
    private record Ancestors(UUID id, boolean taskCancelled, boolean batchCancelled, String shape) {}
}
