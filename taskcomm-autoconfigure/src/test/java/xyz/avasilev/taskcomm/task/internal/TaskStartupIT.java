package xyz.avasilev.taskcomm.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.task.api.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.SearchableField;
import xyz.avasilev.taskcomm.task.api.Task;

/**
 * Синхронизация белого списка полей поиска при старте (docs/adr/0037-search-field-registry.md):
 * вставка по сигнатуре, оформление на месте с {@code updated_at} только при изменении,
 * {@code last_declared_at} каждый старт, новая сигнатура — новая строка, удаление необъявленных
 * только по свойству.
 */
class TaskStartupIT {

    private TaskTestDatabase db;

    @BeforeEach
    void createSchema() {
        this.db = TaskTestDatabase.create();
    }

    @Test
    void syncInsertsBySignatureAndUpdatesOnlyDecoration() throws Exception {
        this.startup(new Report("Номер заказа", "$.orderId"), false);
        final Map<String, Object> first = this.row("$.orderId");

        Thread.sleep(20);
        this.startup(new Report("Номер заказа", "$.orderId"), false);
        final Map<String, Object> unchanged = this.row("$.orderId");

        Thread.sleep(20);
        this.startup(new Report("Заказ", "$.orderId"), false);
        final Map<String, Object> retitled = this.row("$.orderId");

        assertThat(unchanged.get("updated_at")).isEqualTo(first.get("updated_at"));
        assertThat(instant(unchanged, "last_declared_at")).isAfter(instant(first, "last_declared_at"));
        assertThat(retitled.get("field_title")).isEqualTo("Заказ");
        assertThat(instant(retitled, "updated_at")).isAfter(instant(first, "updated_at"));
        assertThat(this.fields()).isEqualTo(1);
    }

    @Test
    void newPathIsNewRowAndOldOneStaysUnlessRemovalIsEnabled() {
        this.startup(new Report("Номер заказа", "$.orderId"), false);
        this.startup(new Report("Номер заказа", "$.order.id"), false);

        assertThat(this.fields()).isEqualTo(2);

        this.startup(new Report("Номер заказа", "$.order.id"), true);

        assertThat(this.fields()).isEqualTo(1);
        assertThat(this.row("$.order.id")).isNotEmpty();
    }

    @Test
    void orphanWarningDoesNotFailStartup() {
        this.db.submit("forgotten", 100);

        this.startup(new Report("Номер заказа", "$.orderId"), false);

        assertThat(this.fields()).isEqualTo(1);
    }

    private void startup(final Report report, final boolean removeUndeclared) {
        new TaskStartup(
                        this.db.dataSource,
                        this.db.transactionManager,
                        this.db.ids,
                        new TaskProcessorRegistry(List.of(report)),
                        removeUndeclared)
                .afterPropertiesSet();
    }

    private int fields() {
        return this.db
                .jdbc
                .sql("SELECT count(*) FROM tc_searchable_field")
                .query(Integer.class)
                .single();
    }

    private Map<String, Object> row(final String path) {
        return this.db
                .jdbc
                .sql("SELECT * FROM tc_searchable_field WHERE json_path = :path")
                .param("path", path)
                .query()
                .singleRow();
    }

    private static Instant instant(final Map<String, Object> row, final String column) {
        final Object value = row.get(column);
        return value instanceof OffsetDateTime offset ? offset.toInstant() : ((java.sql.Timestamp) value).toInstant();
    }

    /** Плоский процессор с одним полем поиска. */
    static final class Report implements FlatTaskProcessor<String, Void> {

        private final String title;

        private final String path;

        Report(final String title, final String path) {
            this.title = title;
            this.path = path;
        }

        @Override
        public String taskType() {
            return TaskTestDatabase.FLAT_TYPE;
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("orderId", this.path, this.title));
        }

        @Override
        public Void process(final Task<String> task, final ProcessingContext context) {
            return null;
        }
    }
}
