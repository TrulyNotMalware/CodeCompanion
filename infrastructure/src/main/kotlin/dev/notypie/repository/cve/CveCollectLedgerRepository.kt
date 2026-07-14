package dev.notypie.repository.cve

import java.time.LocalDateTime

/**
 * Once-per-window collection gate. The collector claims a topic's window before fetching; only the
 * instance that wins the claim collects that window, so overlapping ticks across instances never
 * duplicate the fetch. Idempotency of the events themselves is a separate guarantee
 * (`CveEventRepository.insertIgnore`).
 */
interface CveCollectLedgerRepository {
    /**
     * Atomically claims [topicId]'s collect window starting at [windowStart]. Returns true iff
     * *this* call inserted the row (it owns that topic's window); false means a concurrent instance
     * already claimed it and this instance must skip the topic for the window. Mirrors
     * `AgendaDispatchRepository.claim`.
     */
    fun claimWindow(topicId: Long, windowStart: LocalDateTime): Boolean

    /**
     * Deletes claim rows whose window started before [cutoff]. The ledger only guards the current
     * window against concurrent instances, so old rows are pure growth (~288/day/topic at the
     * 5-minute default) — the collector prunes them past a retention horizon. Returns rows deleted.
     */
    fun deleteOlderThan(cutoff: LocalDateTime): Int
}
