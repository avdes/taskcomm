/**
 * Публичный API агрегата дела: {@code Execution}, {@code ExecutionRef} и {@code ExecutionService}.
 * Агрегат пассивен — не зависит ни от задач, ни от коммуникаций и не участвует в захвате,
 * повторах и аренде (docs/adr/0038-execution-aggregate.md).
 */
package xyz.avasilev.taskcomm.execution.api;
