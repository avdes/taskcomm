package xyz.avasilev.taskcomm.autoconfigure;

/** Значения {@code taskcomm.results.discard}. */
public enum ResultsDiscard {

    /** Не удалять: результаты детей живут до дропа партиции. */
    NEVER,

    /** Сразу после успешной сборки. */
    IMMEDIATELY,

    /** Через {@code taskcomm.results.discard-after} после успешной сборки. */
    AFTER
}
