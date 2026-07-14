package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import java.time.LocalDateTime

/** One (event, subscriber) pair with a DONE summary that has no cve_delivery row yet. */
data class UndeliveredCveEvent(
    val eventId: Long,
    val userId: String,
    val topicKey: String,
    val topicDisplayName: String,
    val title: String,
    val aiSummary: String?,
)

/**
 * Per-(event, user) delivery ledger. The outbox retries transport but never deduplicates, so the
 * atomic [claim] on unique(event_id, user_id) is the only thing stopping a subscriber from being
 * DMed the same event twice across instances. [findUndelivered] is the delivery read surface even
 * though it selects from cve_event: it fans DONE-summarized events out to subscribers and subtracts
 * the pairs already in this ledger.
 */
interface CveDeliveryRepository {
    /**
     * Atomically claims delivery of [eventId] to [userId] as SENT. `INSERT IGNORE` swallows the
     * duplicate-key error on unique(event_id, user_id), so the affected-row count is the claim
     * signal: true means this call owns the delivery, false means another instance already sent it.
     * The dispatcher runs this inside the same transaction as the outbox save (its `@Transactional`
     * is REQUIRED, so it joins that transaction), so a failed send rolls the claim back and the pair
     * is re-driven on the next tick — the ledger never records a delivery that did not enqueue.
     */
    fun claim(eventId: Long, userId: String): Boolean

    /**
     * The (event, user) pairs eligible for delivery: DONE-summarized events on active topics with the
     * given [deliveryMode], created no earlier than [since] (the delivery horizon that bounds the
     * scan) and summarized before [doneBefore] (the visibility cutoff), fanned out to each subscriber,
     * minus pairs already claimed in this ledger. Ordered by event id then user id, capped at [limit].
     */
    fun findUndelivered(
        deliveryMode: CveDeliveryMode,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent>
}
