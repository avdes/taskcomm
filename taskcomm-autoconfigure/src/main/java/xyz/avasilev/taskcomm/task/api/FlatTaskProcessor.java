package xyz.avasilev.taskcomm.task.api;

/**
 * Плоская задача: процессор выполняет задачу целиком, без подзадач, раскрытия и сборки. Форма
 * контракта в базе — {@code FLAT} (docs/adr/0045-flat-task-processor.md).
 *
 * <pre>{@code
 * @Component
 * public class ReportProcessor implements FlatTaskProcessor<
 *         ReportPeriod,   // T  — параметры задачи
 *         ReportFile> {   // RT — результат задачи; Void — не хранить
 *
 *     public String taskType() {
 *         return "monthly-report";
 *     }
 *
 *     public ReportFile process(Task<ReportPeriod> task, ProcessingContext context) {
 *         return reports.build(task.payload());
 *     }
 * }
 * }</pre>
 *
 * <p><b>Гарантия — at-least-once, не exactly-once</b>: {@link #process} обязан быть идемпотентным.
 *
 * @param <T> параметры задачи
 * @param <RT> результат задачи; {@link Void} — результат не хранить
 */
public non-sealed interface FlatTaskProcessor<T, RT> extends TaskProcessor<T> {

    /**
     * Выполняет задачу. Нормальный возврат — успех; исключение — неуспешная попытка с повтором
     * по настройкам, если повтор не запрещён {@link ProcessingContext#doNotRetry(String)}.
     *
     * @param task задача
     * @param context контекст обработки
     * @return результат задачи; {@code null} не хранится
     */
    RT process(Task<T> task, ProcessingContext context);
}
