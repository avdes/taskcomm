package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.SearchableField;

/** Форма объявления поля поиска: непустое имя и разбираемый путь (docs/adr/0127-search-field-sync.md). */
class SearchFieldDeclarationTest {

    @Test
    void acceptsDottedPathsOfIdentifiers() {
        assertThat(declare("orderId", "$.orderId").field().jsonPath()).isEqualTo("$.orderId");
        assertThat(declare("inn", "$.customer.inn_2").field().jsonPath()).isEqualTo("$.customer.inn_2");
    }

    @Test
    void rejectsPathsOutsideGrammar() {
        for (final String path : new String[] {"orderId", "$", "$.", "$.order..id", "$.items[0]", "$.a-b", "$.'x'"}) {
            assertThatThrownBy(() -> declare("field", path))
                    .as(path)
                    .isInstanceOf(TaskcommConfigurationException.class)
                    .hasMessageContaining("не разбирается");
        }
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> declare(" ", "$.x"))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("пустое имя");
    }

    private static SearchFieldDeclaration declare(final String name, final String path) {
        return new SearchFieldDeclaration(TaskLevel.TASK, "report", SearchableField.string(name, path));
    }
}
