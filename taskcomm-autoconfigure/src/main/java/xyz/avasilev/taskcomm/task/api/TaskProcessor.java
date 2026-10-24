package xyz.avasilev.taskcomm.task.api;

import java.util.List;

/**
 * Процессор задачи — код потребителя для одного типа задач. Запечатанный базовый контракт:
 * реализуется один из трёх наследников, и форму работы выбирает тип задачи, а не конфигурация
 * (docs/adr/0007-processor-contracts.md, docs/adr/0059-collect-contract.md).
 *
 * <ul>
 *   <li>{@link FlatTaskProcessor} — задача выполняется целиком, подзадач нет;
 *   <li>{@link UnitTaskProcessor} — задача раскрывается в подзадачи;
 *   <li>{@link BatchTaskProcessor} — задача раскрывается в группы, группы — в подзадачи.
 * </ul>
 *
 * <p>Процессор объявляется обычным бином; два процессора с одним {@link #taskType()} — падение
 * при старте. <b>Процессор не удаляется никогда</b>, пока в базе есть задачи его типа: он и есть
 * их исполнитель. Контракт у живого типа не меняют — заводят новый тип (docs/consumer-contract.md).
 *
 * <p><b>Гарантия — at-least-once, не exactly-once.</b> Аренда может истечь у живого, но медленного
 * воркера, и ту же работу подберёт другой. Каждый метод процессора обязан быть идемпотентным.
 *
 * @param <T> параметры задачи
 */
public sealed interface TaskProcessor<T> permits FlatTaskProcessor, UnitTaskProcessor, BatchTaskProcessor {

    /**
     * Тип задачи, который обслуживает процессор, — ключ всей системы. Не меняется у работающего типа.
     *
     * @return тип задачи
     */
    String taskType();

    /**
     * Поля нагрузки задачи, по которым разрешён поиск (docs/adr/0037-search-field-registry.md).
     * Объявленное попадает в реестр в базе и обслуживает поиск по истории, даже когда объявление
     * из кода убрано.
     *
     * @return поля поиска по нагрузке задачи; по умолчанию — ни одного
     */
    default List<SearchableField> searchableTaskFields() {
        return List.of();
    }
}
