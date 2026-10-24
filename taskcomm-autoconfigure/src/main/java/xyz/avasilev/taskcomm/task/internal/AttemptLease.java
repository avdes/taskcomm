package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.UUID;

/**
 * Аренда открытой попытки — всё, что нужно, чтобы её продлить и закрыть
 * (docs/adr/0046-lease-in-attempt.md).
 *
 * @param attemptId идентификатор попытки
 * @param token токен аренды, новый на каждый захват
 * @param duration на сколько продлевается аренда
 * @param maxRenewals предел обработки в продлениях
 */
record AttemptLease(UUID attemptId, UUID token, Duration duration, int maxRenewals) {}
