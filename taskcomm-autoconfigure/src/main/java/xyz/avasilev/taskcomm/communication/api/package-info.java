/**
 * Публичный API подсистемы коммуникаций: {@code Communication} с направлениями {@code Inbound}
 * и {@code Outbound}, {@code CommunicationProcessor} и SPI хранилища. Модель у коммуникаций
 * плоская и намеренно не унифицирована с иерархией задач (docs/adr/0010-separate-engines.md).
 */
package xyz.avasilev.taskcomm.communication.api;
