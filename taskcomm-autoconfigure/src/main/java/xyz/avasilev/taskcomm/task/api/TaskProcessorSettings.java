package xyz.avasilev.taskcomm.task.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройки процессора задачи по умолчанию — от автора, который знает профиль своих фаз
 * (docs/adr/0061-processor-configuration-annotations.md, docs/adr/0113-task-processor-settings-attributes.md).
 *
 * <p>Вешается на класс процессора и на его методы; метод перекрывает класс. <b>Любое значение
 * из {@code application.yml} сильнее любой аннотации</b> — порядок разрешения из пяти уровней,
 * итог по каждому типу и фазе печатается при старте.
 *
 * <p>Незаданный атрибут — ноль или пустая строка: для каждой настройки ноль и так недопустим.
 * Длительности — в формате {@code Duration} Spring Boot: {@code "30s"}, {@code "2h"}.
 * Для групп настроек есть сокращения {@link TaskPriority}, {@link TaskLease}, {@link TaskRetry};
 * несколько аннотаций на одном месте складываются, один атрибут, заданный дважды, — падение при
 * старте. Приоритет — только на классе: на методе он молча ничего не делал бы, поэтому там это
 * падение при старте (docs/adr/0084-priority-only-per-type.md).
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TaskProcessorSettings {

    /**
     * Приоритет задач типа по умолчанию. Шкала по возрастанию: <b>меньше значит важнее</b>.
     * Только на классе.
     *
     * @return приоритет; {@code 0} — не задан
     */
    int priority() default 0;

    /**
     * Длительность аренды.
     *
     * @return длительность; пусто — не задана
     */
    String leaseDuration() default "";

    /**
     * Интервал heartbeat — заметно меньше аренды, порядка трети.
     *
     * @return интервал; пусто — не задан
     */
    String heartbeatInterval() default "";

    /**
     * Предел обработки одной попытки: дальше аренда не продлевается и попытка закрывается неуспехом.
     *
     * @return предел; пусто — не задан
     */
    String maxExecutionTime() default "";

    /**
     * Лимит попыток в цикле повторов, не меньше единицы.
     *
     * @return лимит; {@code 0} — не задан
     */
    int maxAttempts() default 0;

    /**
     * Первая задержка backoff.
     *
     * @return задержка; пусто — не задана
     */
    String initialDelay() default "";

    /**
     * Множитель backoff, не меньше единицы.
     *
     * @return множитель; {@code 0} — не задан
     */
    double multiplier() default 0;

    /**
     * Верхняя граница задержки backoff.
     *
     * @return граница; пусто — не задана
     */
    String maxDelay() default "";

    /**
     * Доля джиттера в {@code (0, 1]}. Джиттер обязателен: без него тысяча подзадач проснётся разом.
     *
     * @return доля; {@code 0} — не задана
     */
    double jitter() default 0;
}
