package xyz.avasilev.taskcomm.task.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.SearchableField;
import xyz.avasilev.taskcomm.task.api.TaskProcessor;
import xyz.avasilev.taskcomm.task.api.UnitTaskProcessor;

/**
 * Объявление поля поиска процессором: уровень, тип задачи и само поле
 * (docs/adr/0037-search-field-registry.md). Сигнатура — уровень, тип, имя, тип значения и путь;
 * подпись и описание — оформление.
 *
 * <p>Проверяется только форма объявления: непустое имя и разбираемый путь. Путь — {@code $} и одно
 * или больше имён через точку, каждое — латинская буква или подчёркивание, затем буквы, цифры,
 * подчёркивания (docs/adr/0127-search-field-sync.md). Узкая грамматика подставляется в SQL
 * без кавычек и экранирования; расширить её можно, не ломая уже объявленного.
 *
 * @param level уровень сущности, чья нагрузка ищется
 * @param taskType тип задачи
 * @param field поле
 */
record SearchFieldDeclaration(TaskLevel level, String taskType, SearchableField field) {

    private static final Pattern JSON_PATH = Pattern.compile("\\$(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    /**
     * Проверяет форму объявления.
     *
     * @param level уровень
     * @param taskType тип задачи
     * @param field поле
     * @throws TaskcommConfigurationException если имя пустое или путь не разбирается
     */
    SearchFieldDeclaration {
        if (field.name().isBlank()) {
            throw new TaskcommConfigurationException("Тип задачи '" + taskType + "', уровень " + level
                    + ": пустое имя поля поиска с путём " + field.jsonPath());
        }
        if (!JSON_PATH.matcher(field.jsonPath()).matches()) {
            throw new TaskcommConfigurationException("Тип задачи '" + taskType + "', уровень " + level + ", поле '"
                    + field.name() + "': путь '" + field.jsonPath() + "' не разбирается. Путь — $ и имена через "
                    + "точку, например $.order.id; имя — латинская буква или _, затем буквы, цифры, _.");
        }
    }

    /**
     * Все объявления процессора — по уровням его контракта. Разбор запечатанной иерархии без
     * ветки {@code default} (docs/adr/0007-processor-contracts.md).
     *
     * @param processor процессор
     * @return объявления
     * @throws TaskcommConfigurationException если объявление неверной формы
     */
    static List<SearchFieldDeclaration> of(final TaskProcessor<?> processor) {
        final List<SearchFieldDeclaration> declarations = new ArrayList<>();
        add(declarations, TaskLevel.TASK, processor, processor.searchableTaskFields());
        switch (processor) {
            case FlatTaskProcessor<?, ?> _ -> {
                // уровней ниже задачи нет
            }
            case UnitTaskProcessor<?, ?, ?, ?> unit ->
                add(declarations, TaskLevel.UNIT, processor, unit.searchableUnitFields());
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> batch -> {
                add(declarations, TaskLevel.BATCH, processor, batch.searchableBatchFields());
                add(declarations, TaskLevel.UNIT, processor, batch.searchableUnitFields());
            }
        }
        return List.copyOf(declarations);
    }

    private static void add(
            final List<SearchFieldDeclaration> declarations,
            final TaskLevel level,
            final TaskProcessor<?> processor,
            final List<SearchableField> fields) {
        fields.forEach(field -> declarations.add(new SearchFieldDeclaration(level, processor.taskType(), field)));
    }
}
