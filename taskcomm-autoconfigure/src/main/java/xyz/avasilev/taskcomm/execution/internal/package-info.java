/**
 * Внутренняя реализация агрегата дела: {@code ExecutionRepository}, держатель текущего дела
 * и генератор UUIDv7. Не публичный API; кроме {@code autoconfigure}, доступен только внутренним
 * пакетам обеих подсистем — ради общего держателя текущего дела и генератора идентификаторов
 * (docs/adr/0087-architecture-rules.md, docs/adr/0114-uuidv7-generator-in-execution-internal.md).
 */
package xyz.avasilev.taskcomm.execution.internal;
