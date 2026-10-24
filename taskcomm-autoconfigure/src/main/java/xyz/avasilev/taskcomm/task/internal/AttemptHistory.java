package xyz.avasilev.taskcomm.task.internal;

/**
 * Место попытки среди попыток своей фазы — для {@code attemptNumber()} и {@code isRetry()}
 * контекста обработки (docs/adr/0112-attempt-number-in-processing-context.md). Считается при
 * захвате, поэтому контекст отвечает из памяти.
 *
 * @param number номер среди попыток той же фазы с начала текущего цикла повторов, с единицы
 * @param retry {@code true}, если у сущности была более ранняя попытка той же фазы за всю историю,
 *     включая время до ручного перезапуска
 */
record AttemptHistory(int number, boolean retry) {}
