package xyz.avasilev.taskcomm.schema.internal;

import java.util.Arrays;
import java.util.List;

/**
 * Девять партиционированных таблиц (docs/rules/partitioning.md). Партиции одного диапазона
 * создаются и удаляются вместе; порядок перечисления — постоянный порядок блокировок при удалении.
 *
 * <p>Незавершённые статусы есть только у четырёх подчинённых таблиц: это захватываемые статусы
 * плюс {@code AWAITING_PARENT} и {@code AWAITING_RESPONSE}. По ним проверяется, можно ли удалить
 * диапазон и сдвинуть границу активных партиций. У остальных таблиц статуса нет.
 */
enum PartitionedTable {
    EXECUTION("tc_execution"),
    TASK("tc_task", "PENDING", "RUNNING", "AWAITING_CHILDREN"),
    TASK_BATCH("tc_task_batch", "AWAITING_PARENT", "PENDING", "RUNNING", "AWAITING_CHILDREN"),
    TASK_UNIT("tc_task_unit", "AWAITING_PARENT", "PENDING", "RUNNING"),
    COMMUNICATION("tc_communication", "PENDING", "RUNNING", "AWAITING_RESPONSE"),
    TASK_ATTEMPT("tc_task_attempt"),
    COMMUNICATION_ATTEMPT("tc_communication_attempt"),
    TASK_RESULT("tc_task_result"),
    COMMUNICATION_DEDUP("tc_communication_dedup");

    private final String tableName;

    /** Условие «строка незавершена» или пустая строка, если статуса у таблицы нет. */
    private final String unfinishedCondition;

    PartitionedTable(final String tableName, final String... unfinishedStatuses) {
        this.tableName = tableName;
        this.unfinishedCondition =
                unfinishedStatuses.length == 0 ? "" : "status IN ('" + String.join("', '", unfinishedStatuses) + "')";
    }

    String tableName() {
        return this.tableName;
    }

    boolean hasStatus() {
        return !this.unfinishedCondition.isEmpty();
    }

    /**
     * Условие «строка незавершена» для этой таблицы. Значения — константы, не ввод.
     *
     * @return фрагмент SQL вида {@code status IN ('PENDING', ...)}
     */
    String unfinishedCondition() {
        return this.unfinishedCondition;
    }

    static List<String> tableNames() {
        return Arrays.stream(values()).map(PartitionedTable::tableName).toList();
    }

    static List<PartitionedTable> withStatus() {
        return Arrays.stream(values()).filter(PartitionedTable::hasStatus).toList();
    }
}
