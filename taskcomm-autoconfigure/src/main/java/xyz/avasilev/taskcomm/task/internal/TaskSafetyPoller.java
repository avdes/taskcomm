package xyz.avasilev.taskcomm.task.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Подстраховочный поллер задач (docs/adr/0126-safety-poller.md): доводит шаги каскада, которые
 * воркер делает после коммита и не довёл, потому что экземпляр упал между коммитом и шагом
 * (docs/adr/0124-parent-ready-after-child-commit.md).
 *
 * <ul>
 *   <li><b>Дети закрытой попытки раскрытия</b>, оставшиеся в {@code AWAITING_PARENT}: после успеха —
 *       в {@code PENDING} или, у отменённой сущности, в {@code SKIPPED}; после любого другого
 *       исхода — в {@code ABANDONED} (docs/adr/0068-expansion-children-statuses.md).
 *   <li><b>Родители в {@code AWAITING_CHILDREN}</b>, у последнего раскрытия которых не осталось
 *       незавершённых детей: в захват сборки или, у отменённых, в {@code CANCELLED}
 *       (docs/adr/0069-parent-phase-column.md).
 *   <li><b>Отменённые родители в {@code AWAITING_CHILDREN}</b>: перевод их ждущих потомков
 *       повторяется — прерванный при отмене перевод доводится
 *       (docs/adr/0070-cancellation-on-any-entity.md).
 * </ul>
 *
 * <p>Всё — только то, что дольше выдержки не двигалось: живого воркера подстраховка не перебивает.
 * Все шаги условны по статусу и идемпотентны, поэтому подстраховки нескольких экземпляров друг
 * другу не мешают. Ищет только в активных партициях (docs/adr/0101-active-partition-horizon.md).
 */
@Slf4j
class TaskSafetyPoller {

    /** Сколько находок каждого вида берёт один такт. */
    static final int LIMIT = 100;

    private static final String CLOSED_EXPANSIONS = """
            SELECT DISTINCT a.level, a.target_id, a.execution_created_at, a.id, a.status, p.task_type
              FROM {child} c
              JOIN tc_task_attempt a ON a.execution_created_at = c.execution_created_at AND a.id = c.parent_attempt_id
              JOIN {parent} p ON p.execution_created_at = a.execution_created_at AND p.id = a.target_id
             WHERE c.status = 'AWAITING_PARENT'
               AND c.execution_created_at >= :activeFrom
               AND a.execution_created_at >= :activeFrom
               AND p.execution_created_at >= :activeFrom
               AND a.finished_at < now() - make_interval(secs => :grace)
             LIMIT :limit
            """;

    /** Родители, которым больше нечего ждать: ни одного незавершённого ребёнка последнего раскрытия. */
    private static final String STUCK_PARENTS = """
            SELECT p.execution_created_at, p.id, p.task_type
              FROM {parent} p
              JOIN LATERAL (SELECT a.id FROM tc_task_attempt a
                             WHERE a.execution_created_at = p.execution_created_at
                               AND a.execution_created_at >= :activeFrom AND a.level = :level
                               AND a.target_id = p.id AND a.phase = 'EXPAND' AND a.status = 'SUCCEEDED'
                             ORDER BY a.started_at DESC
                             LIMIT 1) x ON true
             WHERE p.status = 'AWAITING_CHILDREN'
               AND p.execution_created_at >= :activeFrom
               AND p.updated_at < now() - make_interval(secs => :grace)
            {noUnfinished}
             LIMIT :limit
            """;

    private static final String NO_UNFINISHED = """
               AND NOT EXISTS (SELECT 1 FROM {child} c
                                WHERE c.execution_created_at = p.execution_created_at
                                  AND c.execution_created_at >= :activeFrom AND c.parent_attempt_id = x.id
                                  AND c.status IN ('AWAITING_PARENT', 'PENDING', 'RUNNING', 'AWAITING_CHILDREN'))
            """;

    private static final String CANCELLED_PARENTS = """
            SELECT execution_created_at, id, task_type
              FROM {parent}
             WHERE status = 'AWAITING_CHILDREN'
               AND execution_created_at >= :activeFrom
               AND cancel_requested_at < now() - make_interval(secs => :grace)
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final TaskChildren children;

    private final TaskCancellation cancellation;

    private final SafetySettings settings;

    TaskSafetyPoller(
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager,
            final TaskChildren children,
            final TaskCancellation cancellation,
            final SafetySettings settings) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = TaskSql.transactions(transactionManager);
        this.children = children;
        this.cancellation = cancellation;
        this.settings = settings;
    }

    /** Один такт: все три вида находок, по {@link #LIMIT} каждого на таблицу. */
    void tick() {
        final Instant activeFrom = TaskPartitionHorizon.activeFrom(this.jdbc);
        for (final TaskLevel parent : List.of(TaskLevel.TASK, TaskLevel.BATCH)) {
            for (final TaskLevel child : TaskChildren.childLevels(parent)) {
                this.settleClosedExpansions(activeFrom, parent, child);
            }
            this.finishStuckParents(activeFrom, parent);
            this.redoCancellations(activeFrom, parent);
        }
    }

    /**
     * Запрос закрытых попыток раскрытия уровня {@code parent}, у которых остались дети уровня
     * {@code child} в {@code AWAITING_PARENT}.
     *
     * @param parent уровень родителя
     * @param child уровень детей
     * @return текст запроса
     */
    static String closedExpansionsQuery(final TaskLevel parent, final TaskLevel child) {
        return CLOSED_EXPANSIONS.replace("{child}", child.table()).replace("{parent}", parent.table());
    }

    /**
     * Запрос родителей уровня {@code parent}, которым больше нечего ждать.
     *
     * @param parent уровень родителя
     * @return текст запроса
     */
    static String stuckParentsQuery(final TaskLevel parent) {
        final StringBuilder noUnfinished = new StringBuilder();
        TaskChildren.childLevels(parent)
                .forEach(level -> noUnfinished.append(NO_UNFINISHED.replace("{child}", level.table())));
        return STUCK_PARENTS
                .replace("{parent}", parent.table())
                .replace("{noUnfinished}", noUnfinished.toString().strip());
    }

    /**
     * Запрос отменённых родителей уровня {@code parent} в {@code AWAITING_CHILDREN}.
     *
     * @param parent уровень родителя
     * @return текст запроса
     */
    static String cancelledParentsQuery(final TaskLevel parent) {
        return CANCELLED_PARENTS.replace("{parent}", parent.table());
    }

    private void settleClosedExpansions(final Instant activeFrom, final TaskLevel parent, final TaskLevel child) {
        final List<ClosedExpansion> found = this.search(() -> this.jdbc
                .sql(closedExpansionsQuery(parent, child))
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("grace", TaskSql.seconds(this.settings.grace()))
                .param("limit", LIMIT)
                .query((rs, rowNum) -> new ClosedExpansion(
                        new TaskEntityRef(
                                TaskLevel.valueOf(rs.getString("level")),
                                rs.getObject("target_id", UUID.class),
                                rs.getObject("execution_created_at", OffsetDateTime.class)
                                        .toInstant()),
                        rs.getObject("id", UUID.class),
                        "SUCCEEDED".equals(rs.getString("status")),
                        rs.getString("task_type")))
                .list());
        for (final ClosedExpansion expansion : found) {
            log.info(
                    "Подстраховка: дети попытки раскрытия {} ({} {}) переводятся из AWAITING_PARENT",
                    expansion.attemptId(),
                    expansion.parent().level(),
                    expansion.parent().id());
            if (!expansion.succeeded()) {
                this.children.abandon(expansion.parent(), expansion.attemptId());
            } else {
                TaskCancelFlags.cancelledBy(this.jdbc, expansion.parent())
                        .ifPresentOrElse(
                                by -> {
                                    this.children.skip(
                                            expansion.parent(), expansion.attemptId(), TaskCancelFlags.ancestor(by));
                                    this.children.finishIfDone(expansion.parent(), expansion.taskType());
                                },
                                () -> this.children.release(expansion.parent(), expansion.attemptId()));
            }
        }
    }

    private void finishStuckParents(final Instant activeFrom, final TaskLevel parent) {
        for (final Found stuck : this.find(stuckParentsQuery(parent), activeFrom, parent)) {
            if (this.children.finishIfDone(stuck.entity(), stuck.taskType())) {
                log.info(
                        "Подстраховка: {} {} больше нечего ждать — передан дальше",
                        parent,
                        stuck.entity().id());
            }
        }
    }

    private void redoCancellations(final Instant activeFrom, final TaskLevel parent) {
        for (final Found cancelled : this.find(cancelledParentsQuery(parent), activeFrom, parent)) {
            this.cancellation.descendants(cancelled.entity(), cancelled.taskType());
        }
    }

    private List<Found> find(final String sql, final Instant activeFrom, final TaskLevel parent) {
        return this.search(() -> this.jdbc
                .sql(sql)
                .param("activeFrom", TaskSql.parameter(activeFrom))
                .param("level", parent.name())
                .param("grace", TaskSql.seconds(this.settings.grace()))
                .param("limit", LIMIT)
                .query((rs, rowNum) -> new Found(
                        new TaskEntityRef(
                                parent,
                                rs.getObject("id", UUID.class),
                                rs.getObject("execution_created_at", OffsetDateTime.class)
                                        .toInstant()),
                        rs.getString("task_type")))
                .list());
    }

    /**
     * Поиск в своей короткой транзакции под {@code force_custom_plan}: иначе подготовленный
     * на сервере запрос получил бы общий план, и партиции ниже границы блокировались бы,
     * несмотря на условие (docs/adr/0101-active-partition-horizon.md).
     */
    private <R> List<R> search(final Supplier<List<R>> query) {
        return this.transactions.execute(status -> {
            TaskSql.forceCustomPlan(this.jdbc);
            return query.get();
        });
    }

    /**
     * Закрытая попытка раскрытия, у которой остались дети в {@code AWAITING_PARENT}.
     *
     * @param parent сущность попытки
     * @param attemptId попытка
     * @param succeeded закрыта успехом
     * @param taskType тип задачи
     */
    private record ClosedExpansion(TaskEntityRef parent, UUID attemptId, boolean succeeded, String taskType) {}

    /**
     * Найденный родитель.
     *
     * @param entity ключ строки
     * @param taskType тип задачи
     */
    private record Found(TaskEntityRef entity, String taskType) {}
}
