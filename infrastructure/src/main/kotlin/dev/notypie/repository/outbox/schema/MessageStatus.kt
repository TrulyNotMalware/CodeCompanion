package dev.notypie.repository.outbox.schema

enum class MessageStatus {
    INIT,
    FAILURE,
    SUCCESS,
    PENDING,

    /**
     * Claimed by a poller and currently being dispatched. Rows transition `PENDING → IN_PROGRESS`
     * via an atomic CAS-style UPDATE (only succeeds when the row is still PENDING) so duplicate
     * dispatch is prevented even if multiple pollers race. The status updater listener moves the
     * row to `SUCCESS` or `FAILURE` once the dispatch result is known. Rows stuck in this state
     * past the configured threshold typically indicate a crash mid-dispatch.
     */
    IN_PROGRESS,
}
