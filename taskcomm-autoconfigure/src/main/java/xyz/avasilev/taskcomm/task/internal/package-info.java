/**
 * Внутренняя реализация подсистемы задач: хранилище по обязанностям — создание, захват по аренде,
 * закрытие попытки, heartbeat (docs/adr/0117-task-storage-classes-by-responsibility.md), — поллер,
 * воркер и повторы. Не публичный API — граница проверяется ArchUnit-тестом
 * (docs/adr/0004-public-api-boundary.md), а не только именем пакета.
 */
package xyz.avasilev.taskcomm.task.internal;
