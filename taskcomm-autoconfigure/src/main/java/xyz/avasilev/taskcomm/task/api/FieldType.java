package xyz.avasilev.taskcomm.task.api;

/**
 * Тип поля поиска по нагрузке: задаёт вид сравнения в SQL и выражение индекса
 * (docs/adr/0037-search-field-registry.md). Смена типа у поля — новое объявление, а не правка
 * прежнего.
 */
public enum FieldType {

    /** Строка: сравнение текста значения. */
    STRING,

    /** Число: значение приводится к числу. */
    NUMBER
}
