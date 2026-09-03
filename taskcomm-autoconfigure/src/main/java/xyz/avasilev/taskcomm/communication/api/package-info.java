/**
 * Публичный API подсистемы коммуникаций: {@code Communication} с направлениями {@code Inbound}
 * и {@code Outbound}, запечатанная по транспортам иерархия {@code CommunicationProcessor},
 * значения-запросы приёма, SPI хранилища и {@code CommunicationContext}
 * со {@code skip(reason)}, {@code doNotRetry(reason)}, {@code shouldStop()}
 * и {@code executionRef()}. Модель у коммуникаций плоская и намеренно не унифицирована
 * с иерархией задач (docs/adr/0010-separate-engines.md); контекст обработки здесь свой,
 * а не общий с задачами.
 */
package xyz.avasilev.taskcomm.communication.api;
