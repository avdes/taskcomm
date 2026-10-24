package xyz.avasilev.taskcomm.task.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.task.api.CollectedBatch;
import xyz.avasilev.taskcomm.task.api.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.TaskBatchStatus;
import xyz.avasilev.taskcomm.task.api.TaskUnitStatus;

/**
 * Чтения сборки (docs/adr/0048-collect-phase.md, docs/adr/0059-collect-contract.md): какие дети
 * собираются и их ленивый поток.
 *
 * <p><b>Поток читается страницами по ключу</b> — каждая страница своим коротким запросом
 * по индексу {@code (parent_attempt_id, id)} (docs/adr/0121-collect-reads-children-by-keyset-pages.md).
 * Процессор работает вне транзакции и соединения не держит; снимок не нужен: к началу сборки
 * все дети попытки терминальны, а перезапуск во время сборки отвергается
 * (docs/adr/0097-no-restart-during-collect.md).
 *
 * <p>Результат подзадачи читается прямым соединением — он у неё не более одного за всю жизнь;
 * у группы — результат последней попытки через {@code LATERAL} (docs/adr/0058-task-result-storage.md).
 */
class TaskCollector {

    /** Сколько детей читается одной страницей. */
    static final int PAGE = 1000;

    /** Страница подзадач попытки раскрытия; открыта пакету ради проверки плана. */
    static final String UNITS_PAGE = """
            SELECT u.id, u.payload::text AS payload, u.status, u.status_description, r.result::text AS result
              FROM tc_task_unit u
              LEFT JOIN tc_task_result r ON r.execution_created_at = u.execution_created_at
                   AND r.level = 'UNIT' AND r.target_id = u.id
             WHERE u.execution_created_at = :executionCreatedAt
               AND u.parent_attempt_id = :attemptId
               AND u.id > :after
             ORDER BY u.id
             LIMIT :page
            """;

    /** Страница групп попытки раскрытия; открыта пакету ради проверки плана. */
    static final String BATCHES_PAGE = """
            SELECT b.id, b.payload::text AS payload, b.status, b.status_description, r.result::text AS result
              FROM tc_task_batch b
              LEFT JOIN LATERAL (SELECT a.id FROM tc_task_attempt a
                                  WHERE a.execution_created_at = b.execution_created_at
                                    AND a.level = 'BATCH'
                                    AND a.target_id = b.id
                                    AND (a.status IS NULL OR a.status <> 'LEASE_LOST')
                                  ORDER BY a.started_at DESC
                                  LIMIT 1) last ON true
              LEFT JOIN tc_task_result r ON r.execution_created_at = b.execution_created_at AND r.attempt_id = last.id
             WHERE b.execution_created_at = :executionCreatedAt
               AND b.parent_attempt_id = :attemptId
               AND b.id > :after
             ORDER BY b.id
             LIMIT :page
            """;

    /** Начало ключа: первая страница — дети с идентификатором больше нулевого. */
    private static final UUID START = new UUID(0, 0);

    private static final String LATEST_EXPANSION = """
            SELECT id FROM tc_task_attempt
             WHERE execution_created_at = :executionCreatedAt
               AND level = :level
               AND target_id = :targetId
               AND phase = 'EXPAND'
               AND status = 'SUCCEEDED'
             ORDER BY started_at DESC
             LIMIT 1
            """;

    private final JdbcClient jdbc;

    private final TaskPayloadCodec codec;

    TaskCollector(final DataSource dataSource, final TaskPayloadCodec codec) {
        this.jdbc = JdbcClient.create(dataSource);
        this.codec = codec;
    }

    /**
     * Попытка раскрытия, чьих детей собирает сборка, — последняя успешная.
     *
     * @param parent собираемая сущность
     * @return идентификатор попытки
     * @throws IllegalStateException если успешного раскрытия нет — сборка без него не запускается
     */
    UUID latestExpansion(final TaskEntityRef parent) {
        return this.jdbc
                .sql(LATEST_EXPANSION)
                .param("executionCreatedAt", TaskSql.parameter(parent.executionCreatedAt()))
                .param("level", parent.level().name())
                .param("targetId", parent.id())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "У " + parent.level() + " " + parent.id() + " в фазе сборки нет успешного раскрытия"));
    }

    /**
     * Ленивый поток подзадач попытки раскрытия.
     *
     * @param executionCreatedAt ключ партиционирования
     * @param attemptId попытка раскрытия
     * @param types типы процессора
     * @return поток обёрток
     */
    Stream<CollectedUnit<Object, Object>> units(
            final Instant executionCreatedAt, final UUID attemptId, final ProcessorTypes types) {
        return this.paged(
                UNITS_PAGE,
                executionCreatedAt,
                attemptId,
                rs -> new CollectedUnit<>(
                        rs.getObject("id", UUID.class),
                        this.codec.read(rs.getString("payload"), types.unit()),
                        this.result(rs, types.unitResult()),
                        TaskUnitStatus.valueOf(rs.getString("status")),
                        rs.getString("status_description")));
    }

    /**
     * Ленивый поток групп попытки раскрытия.
     *
     * @param executionCreatedAt ключ партиционирования
     * @param attemptId попытка раскрытия
     * @param types типы процессора
     * @return поток обёрток
     */
    Stream<CollectedBatch<Object, Object>> batches(
            final Instant executionCreatedAt, final UUID attemptId, final ProcessorTypes types) {
        return this.paged(
                BATCHES_PAGE,
                executionCreatedAt,
                attemptId,
                rs -> new CollectedBatch<>(
                        rs.getObject("id", UUID.class),
                        this.codec.read(rs.getString("payload"), types.batch()),
                        this.result(rs, types.batchResult()),
                        TaskBatchStatus.valueOf(rs.getString("status")),
                        rs.getString("status_description")));
    }

    private Object result(final ResultSet rs, final ResolvableType type) throws SQLException {
        final String json = rs.getString("result");
        return json == null ? null : this.codec.read(json, type);
    }

    private <E> Stream<E> paged(
            final String sql, final Instant executionCreatedAt, final UUID attemptId, final Mapper<E> mapper) {
        return StreamSupport.stream(new Pages<>(sql, executionCreatedAt, attemptId, mapper), false);
    }

    /**
     * Строка страницы в обёртку сборки.
     *
     * @param <E> обёртка сборки
     */
    @FunctionalInterface
    private interface Mapper<E> {

        E map(ResultSet rs) throws SQLException;
    }

    /**
     * Обёртка с ключом страницы.
     *
     * @param id идентификатор ребёнка — ключ следующей страницы
     * @param value обёртка
     * @param <E> обёртка сборки
     */
    private record Keyed<E>(UUID id, E value) {}

    /**
     * Страницы потока: следующая читается, когда кончилась текущая.
     *
     * @param <E> обёртка сборки
     */
    private final class Pages<E> extends Spliterators.AbstractSpliterator<E> {

        private final String sql;

        private final Instant executionCreatedAt;

        private final UUID attemptId;

        private final Mapper<E> mapper;

        private final Deque<Keyed<E>> page = new ArrayDeque<>();

        private UUID after = START;

        private boolean exhausted;

        Pages(final String sql, final Instant executionCreatedAt, final UUID attemptId, final Mapper<E> mapper) {
            super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
            this.sql = sql;
            this.executionCreatedAt = executionCreatedAt;
            this.attemptId = attemptId;
            this.mapper = mapper;
        }

        @Override
        public boolean tryAdvance(final Consumer<? super E> action) {
            if (this.page.isEmpty() && !this.exhausted) {
                this.readNext();
            }
            final Keyed<E> head = this.page.poll();
            if (head != null) {
                action.accept(head.value());
            }
            return head != null;
        }

        private void readNext() {
            final List<Keyed<E>> next = TaskCollector.this
                    .jdbc
                    .sql(this.sql)
                    .param("executionCreatedAt", TaskSql.parameter(this.executionCreatedAt))
                    .param("attemptId", this.attemptId)
                    .param("after", this.after)
                    .param("page", PAGE)
                    .query((rs, rowNum) -> new Keyed<>(rs.getObject("id", UUID.class), this.mapper.map(rs)))
                    .list();
            this.exhausted = next.size() < PAGE;
            if (!next.isEmpty()) {
                this.after = next.getLast().id();
            }
            this.page.addAll(next);
        }
    }
}
