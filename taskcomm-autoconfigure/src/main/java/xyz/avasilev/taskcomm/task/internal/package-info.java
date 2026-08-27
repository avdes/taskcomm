/**
 * Внутренняя реализация подсистемы задач: JDBC-репозиторий, поллер, воркер, захват по аренде
 * и повторы. Не публичный API — граница проверяется ArchUnit-тестом
 * (docs/adr/0004-public-api-boundary.md), а не только именем пакета.
 */
package xyz.avasilev.taskcomm.task.internal;
