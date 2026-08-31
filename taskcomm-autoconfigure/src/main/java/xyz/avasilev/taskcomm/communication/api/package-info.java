/**
 * Публичный API подсистемы коммуникаций: {@code Communication} с направлениями {@code Inbound}
 * и {@code Outbound}, запечатанная по транспортам иерархия {@code CommunicationProcessor},
 * {@code CommunicationContext}, значения-запросы приёма и SPI хранилища. Модель у коммуникаций
 * плоская и намеренно не унифицирована с иерархией задач (docs/adr/0010-separate-engines.md);
 * контекст обработки здесь свой, а не общий с задачами.
 */
package xyz.avasilev.taskcomm.communication.api;
