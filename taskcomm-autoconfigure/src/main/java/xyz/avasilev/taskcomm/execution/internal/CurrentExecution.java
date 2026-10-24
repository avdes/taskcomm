package xyz.avasilev.taskcomm.execution.internal;

import java.util.Objects;
import java.util.Optional;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;

/**
 * Держатель текущего дела — неявная привязка потока к делу обрабатываемой сущности
 * (docs/rules/execution.md, docs/adr/0038-execution-aggregate.md).
 *
 * <p>Воркер привязывает поток на время вызова процессора и снимает привязку восстановлением
 * прежнего снимка. Сущность, созданная в этом потоке, попадает в то же дело и получает указатель
 * на создателя. Смены потока привязка не переживает: из чужого пула сущность молча получит новое
 * дело — обход ловушки — явная передача {@link ExecutionRef} (docs/adr/0056-execution-ref.md).
 *
 * <p>Общий для обеих подсистем — единственное, ради чего {@code execution.internal} открыт
 * их внутренним пакетам, вместе с генератором UUIDv7
 * (docs/adr/0114-uuidv7-generator-in-execution-internal.md).
 */
public final class CurrentExecution {

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private CurrentExecution() {}

    /**
     * Привязка текущего потока.
     *
     * @return дело и создатель или пусто, если поток не обрабатывает сущность
     */
    public static Optional<Binding> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Привязывает поток к делу до закрытия возвращённой области.
     *
     * @param binding дело и создатель
     * @return область, закрытие которой восстанавливает прежнюю привязку
     */
    public static Scope bind(final Binding binding) {
        Objects.requireNonNull(binding, "binding");
        final Binding previous = CURRENT.get();
        CURRENT.set(binding);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Привязка потока: дело обрабатываемой сущности и указатель на неё как на создателя.
     *
     * @param execution дело обрабатываемой сущности
     * @param creator обрабатываемая сущность и её попытка — создатель всего, что создано в потоке
     */
    public record Binding(ExecutionRef execution, Origin creator) {

        /**
         * Проверяет, что оба поля заданы.
         *
         * @throws NullPointerException если поле пустое
         */
        public Binding {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(creator, "creator");
        }
    }

    /** Область привязки; закрытие восстанавливает прежнюю привязку потока. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        /** Восстанавливает прежнюю привязку. Исключений не бросает. */
        @Override
        void close();
    }
}
