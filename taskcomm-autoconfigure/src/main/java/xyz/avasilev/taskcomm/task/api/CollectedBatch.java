package xyz.avasilev.taskcomm.task.api;

import java.util.UUID;

/**
 * Группа в потоке сборки задачи (docs/adr/0059-collect-contract.md). В поток идут все терминальные
 * группы последней попытки раскрытия задачи.
 *
 * @param id идентификатор группы
 * @param payload параметры группы
 * @param result результат сборки группы или {@code null} — у неуспешных и у вернувших {@code null}
 * @param status терминальный статус
 * @param statusDescription причина статуса или {@code null}
 * @param <B> параметры группы
 * @param <RB> результат сборки группы
 */
public record CollectedBatch<B, RB>(UUID id, B payload, RB result, TaskBatchStatus status, String statusDescription) {}
