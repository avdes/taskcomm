package xyz.avasilev.taskcomm.task.internal;

import org.springframework.core.ResolvableType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TaskcommException;

/**
 * Перевод нагрузок и результатов задач в JSON и обратно (docs/adr/0012-payload-storage.md,
 * docs/adr/0092-jackson-3-liquibase-compatibility.md). Тип при чтении — из дженерика процессора.
 *
 * <p>Маппер — собственный, поле этого объекта, а не бин: формат хранимой нагрузки не зависит
 * от настроек веб-слоя потребителя. Заменить его можно только бином под
 * {@code @TaskcommPayloadMapper}, и это меняет формат хранимых данных
 * (docs/adr/0096-no-foreign-type-beans.md).
 */
public final class TaskPayloadCodec {

    private final JsonMapper mapper;

    /**
     * Собирает кодек.
     *
     * @param mapper маппер потребителя под {@code @TaskcommPayloadMapper} или собственный
     */
    public TaskPayloadCodec(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Нагрузка в JSON.
     *
     * @param value нагрузка, в том числе {@code null}
     * @return JSON
     * @throws TaskcommException если значение не сериализуется
     */
    String write(final Object value) {
        try {
            return this.mapper.writeValueAsString(value);
        } catch (final JacksonException failure) {
            throw new TaskcommException(
                    "Не удалось записать в JSON значение типа "
                            + (value == null ? "null" : value.getClass().getName()) + ": " + failure.getMessage(),
                    failure);
        }
    }

    /**
     * Результат в JSON по правилу хранения: строка пишется для всего, что вернулось не {@code null}
     * (docs/adr/0058-task-result-storage.md). У {@code Void} значение всегда {@code null}.
     *
     * @param result результат метода процессора
     * @return JSON или {@code null}, если хранить нечего
     */
    String writeResult(final Object result) {
        return result == null ? null : this.write(result);
    }

    /**
     * JSON в значение типа из дженерика процессора.
     *
     * @param json JSON
     * @param type тип
     * @return значение
     * @throws TaskcommException если JSON не разбирается в этот тип
     */
    Object read(final String json, final ResolvableType type) {
        try {
            return this.mapper.readValue(json, this.mapper.getTypeFactory().constructType(type.getType()));
        } catch (final JacksonException failure) {
            throw new TaskcommException("Не удалось разобрать JSON в " + type + ": " + failure.getMessage(), failure);
        }
    }
}
