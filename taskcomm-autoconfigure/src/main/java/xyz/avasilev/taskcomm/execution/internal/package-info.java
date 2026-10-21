/**
 * Внутренняя реализация агрегата дела: {@code ExecutionRepository} и держатель текущего дела.
 * Не публичный API; кроме {@code autoconfigure}, доступен только внутренним пакетам обеих
 * подсистем — ради общего держателя текущего дела (docs/adr/0087-architecture-rules.md).
 */
package xyz.avasilev.taskcomm.execution.internal;
