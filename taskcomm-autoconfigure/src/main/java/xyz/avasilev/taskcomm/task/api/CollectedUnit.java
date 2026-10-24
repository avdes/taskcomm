package xyz.avasilev.taskcomm.task.api;

import java.util.UUID;

/**
 * Подзадача в потоке сборки: всё, что нужно процессору, чтобы назвать конкретный бизнес-объект
 * в своде (docs/adr/0059-collect-contract.md). В поток идут все терминальные подзадачи, а не только
 * успешные: свод при {@code WARNING} обязан называть упавшие объекты.
 *
 * @param id идентификатор подзадачи
 * @param payload бизнес-объект подзадачи
 * @param result результат её обработки или {@code null} — у неуспешных и у вернувших {@code null}
 * @param status терминальный статус
 * @param statusDescription причина статуса или {@code null}
 * @param <U> бизнес-объект подзадачи
 * @param <RU> результат обработки подзадачи
 */
public record CollectedUnit<U, RU>(UUID id, U payload, RU result, TaskUnitStatus status, String statusDescription) {}
