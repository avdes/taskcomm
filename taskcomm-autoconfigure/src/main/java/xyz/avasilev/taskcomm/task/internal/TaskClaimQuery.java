package xyz.avasilev.taskcomm.task.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Текст запроса такта захвата и его параметры (docs/adr/0129-claim-by-type-share-locking.md).
 * Текст зависит от формы захвата и набора типов поллера и собирается один раз на пару: набор
 * у поллера постоянен.
 */
final class TaskClaimQuery {

    private static final String EXPIRED = """
            expired AS (SELECT a.level, a.execution_created_at, a.target_id, NULL::uuid AS execution_id,
                    coalesce(t.task_type, b.task_type, u.task_type) AS task_type, a.phase, NULL::text AS contract_shape,
                    NULL::smallint AS priority, NULL::timestamptz AS next_attempt_at,
                    NULL::timestamptz AS claim_order_at, NULL::text AS payload, NULL::timestamptz AS created_at,
                    NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id,
                    NULL::text AS origin_relation,
                    coalesce(b.task_id, u.task_id) AS parent_task_id, u.batch_id AS parent_batch_id,
                    coalesce(b.parent_attempt_id, u.parent_attempt_id) AS parent_attempt_id,
                    a.id AS attempt_id, a.locked_by, a.lease_renewals
               FROM tc_task_attempt a
               LEFT JOIN tc_task t ON a.level = 'TASK' AND t.execution_created_at >= :activeFrom
                    AND t.execution_created_at < :activeTo
                    AND t.execution_created_at = a.execution_created_at AND t.id = a.target_id
               LEFT JOIN tc_task_batch b ON a.level = 'BATCH' AND b.execution_created_at >= :activeFrom
                    AND b.execution_created_at < :activeTo
                    AND b.execution_created_at = a.execution_created_at AND b.id = a.target_id
               LEFT JOIN tc_task_unit u ON a.level = 'UNIT' AND u.execution_created_at >= :activeFrom
                    AND u.execution_created_at < :activeTo
                    AND u.execution_created_at = a.execution_created_at AND u.id = a.target_id
              WHERE a.finished_at IS NULL
                AND a.locked_until < now()
                AND a.execution_created_at >= :activeFrom
                AND a.execution_created_at < :activeTo
              ORDER BY a.locked_until
              LIMIT :expiredLimit
                FOR UPDATE OF a SKIP LOCKED)
            """;

    /**
     * Сами ветки — выборки из CTE: блокировать строки ({@code FOR UPDATE}) ветка {@code UNION ALL}
     * в PostgreSQL не может, а CTE может.
     */
    private static final String BRANCHES = """

            SELECT * FROM tc_task_candidates
            UNION ALL SELECT * FROM tc_task_batch_candidates
            UNION ALL SELECT * FROM tc_task_unit_candidates
            UNION ALL SELECT * FROM expired
            """;

    /** Указатель на создателя есть только у задачи (docs/adr/0064-entity-origin-links.md). */
    private static final String TASK_ORIGIN = "e.origin_type, e.origin_id, e.origin_attempt_id, e.origin_relation";

    private static final String NO_ORIGIN =
            "NULL::text AS origin_type, NULL::uuid AS origin_id, NULL::uuid AS origin_attempt_id, "
                    + "NULL::text AS origin_relation";

    /** Родство детей раскрытия (docs/adr/0047-children-belong-to-attempt.md); у задачи его нет. */
    private static final String NO_PARENT =
            "NULL::uuid AS parent_task_id, NULL::uuid AS parent_batch_id, NULL::uuid AS parent_attempt_id";

    private static final String BATCH_PARENT =
            "e.task_id AS parent_task_id, NULL::uuid AS parent_batch_id, e.parent_attempt_id";

    private static final String UNIT_PARENT =
            "e.task_id AS parent_task_id, e.batch_id AS parent_batch_id, e.parent_attempt_id";

    /** Тексты по форме и набору типов. */
    private final Map<Key, String> texts = new ConcurrentHashMap<>();

    /**
     * Текст запроса такта для поллера — из кеша.
     *
     * @param ordering порядок первой ветки
     * @param types типы поллера, в порядке их параметров
     * @return текст запроса
     */
    String text(final TaskClaimer.Ordering ordering, final List<String> types) {
        return this.texts.computeIfAbsent(new Key(ordering, types), key -> branches(ordering, types));
    }

    /**
     * Запрос такта целиком — обе ветки. Параметры связывает {@link #bind}. Открыт пакету ради
     * прототипов отсечения партиций и блокировок, которые проверяют планы и блокировки ровно
     * этого текста.
     *
     * @param ordering порядок первой ветки
     * @param types типы поллера, в порядке номеров их параметров
     * @return текст запроса
     */
    static String branches(final TaskClaimer.Ordering ordering, final List<String> types) {
        final List<String> ctes = new ArrayList<>();
        ctes.addAll(candidates(TaskLevel.TASK, "e.phase", "e.contract_shape", ordering, types.size()));
        ctes.addAll(candidates(TaskLevel.BATCH, "e.phase", "NULL::text", ordering, types.size()));
        ctes.addAll(candidates(TaskLevel.UNIT, "'PROCESS'", "NULL::text", ordering, types.size()));
        ctes.add(EXPIRED);
        return "WITH " + String.join(",\n", ctes) + BRANCHES;
    }

    /**
     * Связывает параметры запроса такта.
     *
     * @param statement запрос из {@link #branches}
     * @param range диапазон активных партиций: граница и верхний предел
     * @param types типы поллера — в том же порядке, что при сборке текста
     * @param limit сколько захватить на каждом уровне
     * @param expiredLimit сколько брошенных попыток закрыть
     * @return запрос с параметрами
     */
    static JdbcClient.StatementSpec bind(
            final JdbcClient.StatementSpec statement,
            final TaskPartitionHorizon.ActiveRange range,
            final List<String> types,
            final int limit,
            final int expiredLimit) {
        statement
                .param("activeFrom", TaskSql.parameter(range.from()))
                .param("activeTo", TaskSql.parameter(range.to()))
                .param("limit", limit)
                .param("expiredLimit", expiredLimit);
        for (int n = 0; n < types.size(); n++) {
            statement.param("type" + n, types.get(n));
        }
        return statement;
    }

    /**
     * Кандидаты первой ветки одного уровня — CTE {@code <таблица>_candidates} и те, из которых она
     * собрана (docs/adr/0129-claim-by-type-share-locking.md).
     *
     * <ul>
     *   <li>Без типов — пустая выборка.
     *   <li>Один тип — его первые {@code limit} строк под блокировкой: доля одного типа и есть лимит.
     *   <li>Несколько — {@code _head}: у каждого типа первые {@code limit} строк без блокировок, слитые
     *       в порядке захвата и обрезанные до {@code limit}; {@code _share}: сколько строк каждого
     *       типа в слитой голове; {@code _typeN}: каждый тип со {@code SKIP LOCKED} блокирует свою
     *       долю, а занятые другим экземпляром строки добирает из следующих своих.
     * </ul>
     */
    private static List<String> candidates(
            final TaskLevel level,
            final String phase,
            final String contractShape,
            final TaskClaimer.Ordering ordering,
            final int typeCount) {
        final String prefix = level.table();
        final String select = """
                SELECT '%1$s' AS level, e.execution_created_at, e.id AS target_id, e.execution_id,
                        e.task_type, %2$s AS phase, %3$s AS contract_shape, e.priority, e.next_attempt_at,
                        e.claim_order_at, e.payload::text AS payload, e.created_at, %5$s, %6$s,
                        NULL::uuid AS attempt_id, NULL::text AS locked_by, NULL::integer AS lease_renewals
                   FROM %4$s e
                """.formatted(
                        level.name(),
                        phase,
                        contractShape,
                        level.table(),
                        level == TaskLevel.TASK ? TASK_ORIGIN : NO_ORIGIN,
                        parent(level));
        if (typeCount == 0) {
            return List.of(prefix + "_candidates AS (" + select + " WHERE false)");
        }
        if (typeCount == 1) {
            return List.of(prefix + "_candidates AS (" + select + ready(0) + ordering.orderBy("e")
                    + " LIMIT :limit FOR UPDATE OF e SKIP LOCKED)");
        }
        final List<String> ctes = new ArrayList<>();
        ctes.add(prefix + "_head AS (SELECT h.task_type FROM ("
                + IntStream.range(0, typeCount)
                        .mapToObj(n -> "(SELECT e.task_type, e.priority, e.next_attempt_at, e.claim_order_at FROM "
                                + level.table() + " e" + ready(n) + ordering.orderBy("e") + " LIMIT :limit)")
                        .collect(Collectors.joining("\n UNION ALL "))
                + ") h" + ordering.orderBy("h") + " LIMIT :limit)");
        ctes.add(prefix + "_share AS (SELECT task_type, count(*) AS n FROM " + prefix + "_head GROUP BY task_type)");
        for (int n = 0; n < typeCount; n++) {
            ctes.add(prefix + "_type" + n + " AS (" + select + ready(n) + ordering.orderBy("e")
                    + " LIMIT (SELECT coalesce(max(s.n), 0) FROM " + prefix + "_share s WHERE s.task_type = :type"
                    + n + ") FOR UPDATE OF e SKIP LOCKED)");
        }
        ctes.add(prefix + "_candidates AS ("
                + IntStream.range(0, typeCount)
                        .mapToObj(n -> "SELECT * FROM " + prefix + "_type" + n)
                        .collect(Collectors.joining(" UNION ALL "))
                + ")");
        return ctes;
    }

    /** Готовая строка типа с этим номером параметра, без открытой попытки; граница — на каждой таблице. */
    private static String ready(final int type) {
        return """
                  WHERE e.status = 'PENDING'
                    AND e.task_type = :type%d
                    AND e.next_attempt_at <= now()
                    AND e.execution_created_at >= :activeFrom
                    AND e.execution_created_at < :activeTo
                    AND NOT EXISTS (SELECT 1 FROM tc_task_attempt a
                                     WHERE a.execution_created_at >= :activeFrom
                                       AND a.execution_created_at < :activeTo
                                       AND a.execution_created_at = e.execution_created_at
                                       AND a.target_id = e.id
                                       AND a.finished_at IS NULL)
                """.formatted(type);
    }

    private static String parent(final TaskLevel level) {
        return switch (level) {
            case TASK -> NO_PARENT;
            case BATCH -> BATCH_PARENT;
            case UNIT -> UNIT_PARENT;
        };
    }

    /**
     * Ключ текста запроса.
     *
     * @param ordering порядок первой ветки
     * @param types типы поллера по порядку
     */
    private record Key(TaskClaimer.Ordering ordering, List<String> types) {}
}
