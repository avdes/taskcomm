/**
 * Публичный API подсистемы задач: {@code Task}, {@code TaskBatch}, {@code TaskUnit}, три
 * контракта {@code TaskProcessor}, {@code Emitter}, {@code ProcessingContext}
 * и {@code SearchableField}.
 * Это единственная часть подсистемы, на которую опирается потребитель стартера — реализация
 * живёт в соседнем пакете {@code internal} и обратной совместимости не обещает.
 */
package xyz.avasilev.taskcomm.task.api;
