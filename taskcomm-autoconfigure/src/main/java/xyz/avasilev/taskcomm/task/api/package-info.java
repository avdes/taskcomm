/**
 * Публичный API подсистемы задач: {@code Task}, {@code TaskBatch}, {@code TaskUnit}, три
 * контракта {@code TaskProcessor}, {@code Emitter}, {@code SearchableField}, обёртки потока
 * сборки {@code CollectedUnit} и {@code CollectedBatch}, а также {@code ProcessingContext}
 * со {@code skip(reason)}, {@code doNotRetry(reason)}, {@code shouldStop()}
 * и {@code executionRef()}.
 * Это единственная часть подсистемы, на которую опирается потребитель стартера — реализация
 * живёт в соседнем пакете {@code internal} и обратной совместимости не обещает.
 */
package xyz.avasilev.taskcomm.task.api;
