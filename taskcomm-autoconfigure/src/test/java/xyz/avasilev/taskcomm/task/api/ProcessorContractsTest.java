package xyz.avasilev.taskcomm.task.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Критерий готовности этапа 2 — потребитель компилирует процессор, ничего не запуская: здесь три
 * процессора-примера по всем трём контрактам, разбор запечатанной иерархии без ветки {@code default}
 * и типобезопасная постановка по классу процессора (docs/adr/0059, docs/adr/0111).
 */
class ProcessorContractsTest {

    @Test
    void hierarchyIsSealedToThreeContracts() {
        assertThat(TaskProcessor.class.isSealed()).isTrue();
        assertThat(TaskProcessor.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(FlatTaskProcessor.class, UnitTaskProcessor.class, BatchTaskProcessor.class);
    }

    @Test
    void workerStyleSwitchCoversAllContractsWithoutDefault() {
        assertThat(contractShape(new ReportProcessor())).isEqualTo("FLAT");
        assertThat(contractShape(new OrderSyncProcessor())).isEqualTo("UNIT");
        assertThat(contractShape(new PriceSyncProcessor())).isEqualTo("BATCH");
    }

    @Test
    void searchableFieldsAreEmptyByDefaultAndDeclarable() {
        assertThat(new ReportProcessor().searchableTaskFields()).isEmpty();
        assertThat(new OrderSyncProcessor().searchableUnitFields())
                .containsExactly(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
    }

    /** Разбор так же, как его сделает воркер: появление четвёртого контракта сломает компиляцию здесь. */
    private static String contractShape(final TaskProcessor<?> processor) {
        return switch (processor) {
            case FlatTaskProcessor<?, ?> _ -> "FLAT";
            case UnitTaskProcessor<?, ?, ?, ?> _ -> "UNIT";
            case BatchTaskProcessor<?, ?, ?, ?, ?, ?> _ -> "BATCH";
        };
    }

    /**
     * Не вызывается: проверяет компилятором, что постановка по классу выводит тип нагрузки
     * из дженерика процессора и возвращает {@code Task<T>}, а чтения — типизированные значения.
     */
    @SuppressWarnings("unused")
    private static List<Object> typedSubmissionCompiles(final TaskService service, final ProcessingContext context) {
        final Task<SyncPeriod> byClass = service.submit(OrderSyncProcessor.class, new SyncPeriod("2026-10"));
        final Task<SyncPeriod> byType = service.submit("order-sync", new SyncPeriod("2026-10"), context.executionRef());
        final Task<SyncPeriod> withOptions = service.submit(
                OrderSyncProcessor.class,
                new SyncPeriod("2026-10"),
                TaskSubmitOptions.defaults().inNewExecution().priority(10));
        final List<Task<SyncPeriod>> inExecution =
                service.findInExecution(context.executionRef(), OrderSyncProcessor.class);
        final Optional<SyncReport> result = service.result(byClass, SyncReport.class);
        return List.of(byClass, byType, withOptions, inExecution, result);
    }

    record SyncPeriod(String month) {}

    record OrderRef(String orderId) {}

    record OrderOutcome(boolean pushed) {}

    record SyncReport(long pushed) {}

    record SupplierBatch(String supplier) {}

    /** Плоская задача без результата. */
    static class ReportProcessor implements FlatTaskProcessor<SyncPeriod, Void> {

        @Override
        public String taskType() {
            return "monthly-report";
        }

        @Override
        public Void process(final Task<SyncPeriod> task, final ProcessingContext context) {
            return null;
        }
    }

    /** Задача с подзадачами. */
    @TaskRetry(maxAttempts = 5)
    static class OrderSyncProcessor implements UnitTaskProcessor<SyncPeriod, OrderRef, OrderOutcome, SyncReport> {

        @Override
        public String taskType() {
            return "order-sync";
        }

        @Override
        public void expand(
                final Task<SyncPeriod> task, final Emitter<OrderRef> units, final ProcessingContext context) {
            units.emit(new OrderRef("A-1"));
        }

        @Override
        public OrderOutcome process(final TaskUnit<OrderRef> unit, final ProcessingContext context) {
            return new OrderOutcome(!context.isRetry());
        }

        @Override
        public SyncReport collect(
                final Task<SyncPeriod> task,
                final Stream<CollectedUnit<OrderRef, OrderOutcome>> units,
                final ProcessingContext context) {
            return new SyncReport(units.filter(unit -> unit.status() == TaskUnitStatus.SUCCEEDED)
                    .count());
        }

        @Override
        public List<SearchableField> searchableUnitFields() {
            return List.of(SearchableField.string("orderId", "$.orderId", "Номер заказа"));
        }
    }

    /** Задача с группами. */
    static class PriceSyncProcessor
            implements BatchTaskProcessor<SyncPeriod, SupplierBatch, OrderRef, OrderOutcome, Long, SyncReport> {

        @Override
        public String taskType() {
            return "price-sync";
        }

        @Override
        public void expand(
                final Task<SyncPeriod> task, final Emitter<SupplierBatch> batches, final ProcessingContext context) {
            batches.emit(new SupplierBatch("supplier-b"));
        }

        @Override
        public void expandBatch(
                final TaskBatch<SupplierBatch> batch, final Emitter<OrderRef> units, final ProcessingContext context) {
            if (context.shouldStop()) {
                context.skip("отменено");
            }
        }

        @Override
        @TaskLease(maxExecutionTime = "2h")
        public OrderOutcome process(final TaskUnit<OrderRef> unit, final ProcessingContext context) {
            return new OrderOutcome(true);
        }

        @Override
        public Long collectBatch(
                final TaskBatch<SupplierBatch> batch,
                final Stream<CollectedUnit<OrderRef, OrderOutcome>> units,
                final ProcessingContext context) {
            return units.count();
        }

        @Override
        public SyncReport collect(
                final Task<SyncPeriod> task,
                final Stream<CollectedBatch<SupplierBatch, Long>> batches,
                final ProcessingContext context) {
            return new SyncReport(batches.mapToLong(CollectedBatch::result).sum());
        }
    }
}
