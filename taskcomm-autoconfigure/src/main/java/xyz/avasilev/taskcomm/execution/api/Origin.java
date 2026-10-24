package xyz.avasilev.taskcomm.execution.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Указатель на создателя задачи или коммуникации внутри дела (docs/adr/0064-entity-origin-links.md).
 *
 * <p>Есть только у сущностей, созданных в ходе обработки другой сущности того же дела, и у колбэка,
 * ставшего отдельной коммуникацией. У корневых сущностей — созданных кодом потребителя вне
 * процессора — указателя нет, и это норма. Связь не выходит за дело: отказались от привязки или
 * передали другое дело — указателя тоже нет, молча. У групп и подзадач указателя не бывает:
 * их создатель — попытка раскрытия родителя.
 *
 * @param type вид сущности-создателя
 * @param id идентификатор сущности-создателя; она лежит в той же партиции
 * @param attemptId попытка создателя, в ходе которой создана сущность, или {@code null} у колбэка —
 *     его создаёт контроллер потребителя, попытки за ним нет
 * @param relation вид связи
 */
public record Origin(OriginType type, UUID id, UUID attemptId, OriginRelation relation) {

    /**
     * Проверяет обязательные поля.
     *
     * @throws NullPointerException если вид, идентификатор или вид связи пусты
     */
    public Origin {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(relation, "relation");
    }
}
