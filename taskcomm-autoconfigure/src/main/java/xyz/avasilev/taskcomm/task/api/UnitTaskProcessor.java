package xyz.avasilev.taskcomm.task.api;

import java.util.List;
import java.util.stream.Stream;

/**
 * Задача с подзадачами: процессор раскрывает задачу в подзадачи, обрабатывает каждую и собирает
 * свод. Форма контракта в базе — {@code UNIT}. Каждый переход асинхронный:
 * {@code expand → process каждой подзадачи → collect}.
 *
 * <pre>{@code
 * @Component
 * public class OrderSyncProcessor implements UnitTaskProcessor<
 *         SyncPeriod,     // T  — параметры задачи: период
 *         OrderRef,       // U  — один заказ
 *         OrderOutcome,   // RU — что вышло с заказом
 *         SyncReport> {   // RT — свод по задаче
 *
 *     public String taskType() {
 *         return "order-sync";
 *     }
 *
 *     public void expand(Task<SyncPeriod> task, Emitter<OrderRef> units, ProcessingContext context) {
 *         orders.findChanged(task.payload()).forEach(units::emit);
 *     }
 *
 *     public OrderOutcome process(TaskUnit<OrderRef> unit, ProcessingContext context) {
 *         return client.push(unit.payload());
 *     }
 *
 *     public SyncReport collect(
 *             Task<SyncPeriod> task, Stream<CollectedUnit<OrderRef, OrderOutcome>> units,
 *             ProcessingContext context) {
 *         return SyncReport.of(units);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Гарантия — at-least-once, не exactly-once</b>: каждый метод обязан быть идемпотентным.
 *
 * @param <T> параметры задачи
 * @param <U> один бизнес-объект подзадачи
 * @param <RU> результат обработки подзадачи; {@link Void} — не хранить
 * @param <RT> результат сборки задачи; {@link Void} — не хранить
 */
public non-sealed interface UnitTaskProcessor<T, U, RU, RT> extends TaskProcessor<T> {

    /**
     * Раскрывает задачу в подзадачи. Повтор раскрытия ничего не удаляет: новая попытка порождает
     * своих детей, дети неуспешной попытки становятся {@code ABANDONED}. Источник перечисляется
     * с начала при каждой попытке.
     *
     * @param task задача
     * @param units приёмник подзадач; действителен только внутри вызова
     * @param context контекст обработки
     */
    void expand(Task<T> task, Emitter<U> units, ProcessingContext context);

    /**
     * Обрабатывает одну подзадачу. Неуспех повторяет только эту подзадачу.
     *
     * @param unit подзадача
     * @param context контекст обработки
     * @return результат обработки; {@code null} не хранится
     */
    RU process(TaskUnit<U> unit, ProcessingContext context);

    /**
     * Собирает свод, когда у последнего раскрытия не осталось незавершённых подзадач, — в том числе
     * после раскрытия с нулём подзадач. Поток ленивый: подзадач может быть миллион, материализовать
     * его нельзя. Неуспешная сборка делает задачу терминальной и при полностью успешных подзадачах.
     *
     * @param task задача
     * @param units все терминальные подзадачи последнего раскрытия
     * @param context контекст обработки
     * @return результат задачи; {@code null} не хранится
     */
    RT collect(Task<T> task, Stream<CollectedUnit<U, RU>> units, ProcessingContext context);

    /**
     * Поля нагрузки подзадачи, по которым разрешён поиск.
     *
     * @return поля поиска по нагрузке подзадачи; по умолчанию — ни одного
     */
    default List<SearchableField> searchableUnitFields() {
        return List.of();
    }
}
