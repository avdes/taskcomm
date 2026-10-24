package xyz.avasilev.taskcomm.task.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Необязательные параметры постановки задачи для редких сочетаний: дело, «не раньше», приоритет
 * (docs/adr/0111-task-public-api-shape.md). Неизменяемое значение; каждый метод настройки
 * возвращает новое. Само по себе ничего не ставит — передаётся в
 * {@link TaskService#submit(String, Object, TaskSubmitOptions)}.
 *
 * <pre>{@code
 * taskService.submit("order-sync", period, TaskSubmitOptions.defaults()
 *         .inExecution(context.executionRef())
 *         .notBefore(tomorrowMorning)
 *         .priority(10));
 * }</pre>
 */
public final class TaskSubmitOptions {

    private static final TaskSubmitOptions DEFAULTS = new TaskSubmitOptions(null, false, null, 0);

    private final ExecutionRef executionRef;
    private final boolean newExecution;
    private final Instant notBefore;
    private final int priority;

    private TaskSubmitOptions(
            final ExecutionRef executionRef, final boolean newExecution, final Instant notBefore, final int priority) {
        this.executionRef = executionRef;
        this.newExecution = newExecution;
        this.notBefore = notBefore;
        this.priority = priority;
    }

    /**
     * Опции по умолчанию: дело по правилам привязки, без отсрочки, приоритет по умолчанию типа.
     *
     * @return опции по умолчанию
     */
    public static TaskSubmitOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Ставит задачу в указанное дело — из любого потока. Отменяет {@link #inNewExecution()}.
     *
     * @param execution ссылка на дело
     * @return новые опции
     */
    public TaskSubmitOptions inExecution(final ExecutionRef execution) {
        return new TaskSubmitOptions(
                Objects.requireNonNull(execution, "execution"), false, this.notBefore, this.priority);
    }

    /**
     * Отказ от привязки: задача заводит новое дело, даже внутри процессора. Указателя на создателя
     * у неё не будет — связь не выходит за дело. Отменяет {@link #inExecution(ExecutionRef)}.
     *
     * @return новые опции
     */
    public TaskSubmitOptions inNewExecution() {
        return new TaskSubmitOptions(null, true, this.notBefore, this.priority);
    }

    /**
     * Не захватывать задачу раньше момента. Момент сравнивается с часами <b>базы</b>, а не приложения;
     * точность — до такта поллера. Это не планировщик: отсрочка однократная, ручной перезапуск её
     * отменяет. Длительности не принимаются: «через десять минут» от начала долгой транзакции
     * сократилось бы (docs/adr/0050-deferred-start.md, docs/adr/0079-database-clock.md).
     *
     * @param moment момент, не раньше которого задачу можно взять
     * @return новые опции
     */
    public TaskSubmitOptions notBefore(final Instant moment) {
        return new TaskSubmitOptions(
                this.executionRef, this.newExecution, Objects.requireNonNull(moment, "moment"), this.priority);
    }

    /**
     * Приоритет вместо умолчания типа. Шкала по возрастанию: <b>меньше значит важнее</b>, единица
     * важнее сотни. Группы и подзадачи наследуют его от задачи.
     *
     * @param value приоритет, больше нуля
     * @return новые опции
     * @throws IllegalArgumentException если значение не больше нуля
     */
    public TaskSubmitOptions priority(final int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Приоритет должен быть больше нуля, а передан " + value);
        }
        return new TaskSubmitOptions(this.executionRef, this.newExecution, this.notBefore, value);
    }

    /**
     * Явно указанное дело.
     *
     * @return ссылка на дело или пусто
     */
    public Optional<ExecutionRef> executionRef() {
        return Optional.ofNullable(this.executionRef);
    }

    /**
     * Заводить ли новое дело вместо привязки.
     *
     * @return {@code true}, если вызван {@link #inNewExecution()}
     */
    public boolean newExecution() {
        return this.newExecution;
    }

    /**
     * Момент «не раньше».
     *
     * @return момент или пусто
     */
    public Optional<Instant> notBefore() {
        return Optional.ofNullable(this.notBefore);
    }

    /**
     * Явный приоритет.
     *
     * @return приоритет или пусто — тогда умолчание типа
     */
    public Optional<Integer> priority() {
        return this.priority == 0 ? Optional.empty() : Optional.of(this.priority);
    }
}
