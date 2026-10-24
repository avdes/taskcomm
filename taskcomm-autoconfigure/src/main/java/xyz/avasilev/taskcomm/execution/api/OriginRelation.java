package xyz.avasilev.taskcomm.execution.api;

/** Вид связи в {@link Origin} (docs/adr/0064-entity-origin-links.md). */
public enum OriginRelation {

    /** Создана вызовом из процессора, обрабатывавшего создателя. */
    SPAWNED,

    /**
     * Колбэк, нашедший исходную коммуникацию по ключу корреляции, но ставший отдельной входящей:
     * пришёл под другим именем системы или исходная уже не ждала ответа.
     */
    RESPONSE
}
