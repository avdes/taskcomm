package xyz.avasilev.taskcomm.task.api;

import java.util.Objects;

/**
 * Поле нагрузки, по которому процессор разрешает поиск (docs/adr/0037-search-field-registry.md).
 *
 * <p>Имя, путь и тип — сигнатура: они определяют, как находятся уже записанные данные, и потому
 * неизменяемы — другой путь или тип становится новым объявлением, а старое продолжает искать
 * по старым записям. Подпись и описание — оформление для интерфейса и правятся на месте.
 *
 * @param name имя поля в запросе поиска
 * @param jsonPath путь к значению в нагрузке
 * @param type тип поля
 * @param title подпись для интерфейса или {@code null}
 * @param description пояснение для интерфейса или {@code null}
 */
public record SearchableField(String name, String jsonPath, FieldType type, String title, String description) {

    /**
     * Проверяет обязательные поля.
     *
     * @throws NullPointerException если имя, путь или тип пусты
     */
    public SearchableField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jsonPath, "jsonPath");
        Objects.requireNonNull(type, "type");
    }

    /**
     * Строковое поле без оформления.
     *
     * @param name имя поля
     * @param jsonPath путь к значению
     * @return объявление поля
     */
    public static SearchableField string(final String name, final String jsonPath) {
        return new SearchableField(name, jsonPath, FieldType.STRING, null, null);
    }

    /**
     * Строковое поле с подписью.
     *
     * @param name имя поля
     * @param jsonPath путь к значению
     * @param title подпись для интерфейса
     * @return объявление поля
     */
    public static SearchableField string(final String name, final String jsonPath, final String title) {
        return new SearchableField(name, jsonPath, FieldType.STRING, title, null);
    }

    /**
     * Строковое поле с подписью и пояснением.
     *
     * @param name имя поля
     * @param jsonPath путь к значению
     * @param title подпись для интерфейса
     * @param description пояснение для интерфейса
     * @return объявление поля
     */
    public static SearchableField string(
            final String name, final String jsonPath, final String title, final String description) {
        return new SearchableField(name, jsonPath, FieldType.STRING, title, description);
    }

    /**
     * Числовое поле без оформления.
     *
     * @param name имя поля
     * @param jsonPath путь к значению
     * @return объявление поля
     */
    public static SearchableField number(final String name, final String jsonPath) {
        return new SearchableField(name, jsonPath, FieldType.NUMBER, null, null);
    }

    /**
     * Числовое поле с подписью.
     *
     * @param name имя поля
     * @param jsonPath путь к значению
     * @param title подпись для интерфейса
     * @return объявление поля
     */
    public static SearchableField number(final String name, final String jsonPath, final String title) {
        return new SearchableField(name, jsonPath, FieldType.NUMBER, title, null);
    }

    /**
     * Числовое поле с подписью и пояснением.
     *
     * @param name имя поля
     * @param jsonPath путь к значению
     * @param title подпись для интерфейса
     * @param description пояснение для интерфейса
     * @return объявление поля
     */
    public static SearchableField number(
            final String name, final String jsonPath, final String title, final String description) {
        return new SearchableField(name, jsonPath, FieldType.NUMBER, title, description);
    }
}
