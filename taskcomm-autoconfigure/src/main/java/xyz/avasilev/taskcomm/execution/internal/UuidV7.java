package xyz.avasilev.taskcomm.execution.internal;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генератор идентификаторов UUIDv7 (RFC 9562) — для дела, задач, коммуникаций и их попыток
 * (docs/adr/0035-uuidv7-identifiers.md). Один на всё приложение: лежит здесь, рядом с делом,
 * потому что пакет доступен обоим движкам (docs/adr/0114-uuidv7-generator-in-execution-internal.md).
 *
 * <p>Время внутри идентификатора — единственное, что стартер берёт из часов приложения
 * (docs/adr/0079-database-clock.md): оно служит порядку вставки, и с временем в SQL его
 * не сравнивают.
 *
 * <p><b>Монотонен внутри миллисекунды</b>: двенадцать бит {@code rand_a} — счётчик, который
 * с новой миллисекундой начинается со случайного значения в нижней половине диапазона и растёт
 * на единицу с каждым идентификатором. Переполнение счётчика и откат часов назад не ломают
 * порядок: генератор продолжает от последней выданной миллисекунды, забегая вперёд часов, пока
 * они его не догонят.
 */
public final class UuidV7 {

    private static final long VERSION = 0x7000L;

    private static final long VARIANT = 0x8000_0000_0000_0000L;

    private static final int COUNTER_BITS = 12;

    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;

    private static final int COUNTER_START_BOUND = 1 << (COUNTER_BITS - 1);

    private final Clock clock;

    private long lastMillis = -1;

    private int counter;

    /**
     * Создаёт генератор.
     *
     * @param clock часы приложения; в тестах подменяются
     */
    public UuidV7(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Выдаёт следующий идентификатор — строго больше любого выданного этим генератором раньше.
     *
     * @return идентификатор UUIDv7
     */
    public synchronized UUID next() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final long now = this.clock.millis();
        if (now > this.lastMillis) {
            this.lastMillis = now;
            this.counter = random.nextInt(COUNTER_START_BOUND);
        } else if (this.counter < COUNTER_MAX) {
            this.counter++;
        } else {
            this.lastMillis++;
            this.counter = random.nextInt(COUNTER_START_BOUND);
        }
        final long high = (this.lastMillis << 16) | VERSION | this.counter;
        final long low = VARIANT | (random.nextLong() >>> 2);
        return new UUID(high, low);
    }
}
