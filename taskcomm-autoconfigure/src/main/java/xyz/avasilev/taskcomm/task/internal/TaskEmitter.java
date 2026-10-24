package xyz.avasilev.taskcomm.task.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;
import xyz.avasilev.taskcomm.task.api.Emitter;

/**
 * Приёмник детей одного вызова раскрытия (docs/adr/0008-expansion-emitter.md,
 * docs/adr/0068-expansion-children-statuses.md). Копит детей в буфере и сбрасывает его пачками
 * по {@link #FLUSH_SIZE}, каждую своей короткой транзакцией. Размер пачки — константа, а не
 * свойство: требований к нему нет, а свойство добавляется потом без поломки.
 *
 * <p>Сброс условен по открытости попытки. Не прошёл — попытку закрыл переподхват: буфер
 * выбрасывается, пишется WARN, и следующий {@link #emit} бросает исключение. После возврата
 * из метода раскрытия приёмник закрыт, и {@link #emit} тоже бросает исключение.
 *
 * <p>Не потокобезопасен, как и обещает {@link Emitter}.
 */
@Slf4j
final class TaskEmitter implements Emitter<Object> {

    /** Сколько детей вставляется одним сбросом буфера. */
    static final int FLUSH_SIZE = 1000;

    private final ClaimedAttempt attempt;

    private final TaskLevel level;

    private final TaskChildren children;

    private final TaskPayloadCodec codec;

    private final UuidV7 ids;

    private final List<UUID> bufferIds = new ArrayList<>(FLUSH_SIZE);

    private final List<String> bufferPayloads = new ArrayList<>(FLUSH_SIZE);

    private int flushed;

    private volatile boolean closed;

    private boolean lost;

    TaskEmitter(
            final ClaimedAttempt attempt,
            final TaskLevel level,
            final TaskChildren children,
            final TaskPayloadCodec codec,
            final UuidV7 ids) {
        this.attempt = attempt;
        this.level = level;
        this.children = children;
        this.codec = codec;
        this.ids = ids;
    }

    @Override
    public void emit(final Object payload) {
        if (this.closed) {
            throw new IllegalStateException("Emitter действителен только внутри вызова раскрытия: метод уже вернулся. "
                    + "Не сохраняйте его в поле и не передавайте в другой поток.");
        }
        if (this.lost) {
            throw new IllegalStateException(
                    "Попытка раскрытия " + this.attempt.lease().attemptId() + " уже закрыта "
                            + "переподхватом истёкшей аренды: её дети больше не нужны, раскрытие стоит прекратить.");
        }
        this.bufferIds.add(this.ids.next());
        this.bufferPayloads.add(this.codec.write(payload));
        if (this.bufferIds.size() >= FLUSH_SIZE) {
            this.flush();
        }
    }

    /**
     * Финальный сброс после нормального возврата из раскрытия.
     *
     * @return {@code true}, если все дети записаны, а попытка ещё открыта
     */
    boolean finish() {
        this.closed = true;
        if (!this.lost && !this.bufferIds.isEmpty()) {
            this.flush();
        }
        return !this.lost;
    }

    /** Закрывает приёмник без сброса: раскрытие бросило исключение или объявило пропуск. */
    void discard() {
        this.closed = true;
        this.bufferIds.clear();
        this.bufferPayloads.clear();
    }

    /**
     * Сколько детей записано в базу.
     *
     * @return число детей этой попытки
     */
    int flushed() {
        return this.flushed;
    }

    private void flush() {
        final int size = this.bufferIds.size();
        if (this.children.insert(this.attempt, this.level, this.bufferIds, this.bufferPayloads)) {
            this.flushed += size;
        } else {
            this.lost = true;
            log.warn(
                    "Попытка раскрытия {} ({} {} '{}') уже закрыта — аренда истекла. {} детей из буфера "
                            + "выброшены; следующий emit бросит исключение.",
                    this.attempt.lease().attemptId(),
                    this.attempt.target().level(),
                    this.attempt.target().id(),
                    this.attempt.taskType(),
                    size);
        }
        this.bufferIds.clear();
        this.bufferPayloads.clear();
    }
}
