package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.time.LocalDateTime

interface MessageRelayService {
    // Atomically takes up to `wanted` of the relay's free dispatch slots and returns how many it got. Each slot goes back
    // either with a claim handed to batchPendingMessages or through releaseDispatchSlots; claimWithReservedSlots does
    // both. Reserving before claiming is what keeps the poller and the sweep, which run at the same time on the
    // scheduler pool, from both claiming the same free slot (review F3).
    fun reserveDispatchSlots(wanted: Int): Int

    fun releaseDispatchSlots(count: Int)

    // Each claim spends one slot reserved by reserveDispatchSlots.
    fun batchPendingMessages(claims: List<OutboxClaim>)

    fun dispatchClaimed(claim: OutboxClaim)
}

// Reserves up to `wanted` slots, lets `claimRows` claim at most that many rows, queues what it claimed and gives the
// unused slots back, also when claimRows throws (rows it had already claimed then wait for the sweep, unsent). A row
// is claimed only when a slot is held for it, so a claimed row never waits the stuck threshold for a full executor.
inline fun MessageRelayService.claimWithReservedSlots(
    wanted: Int,
    claimRows: (slots: Int) -> List<OutboxClaim>,
): List<OutboxClaim> {
    val reserved = reserveDispatchSlots(wanted = wanted)
    if (reserved <= 0) return emptyList()
    var claims = emptyList<OutboxClaim>()
    try {
        val claimed = claimRows(reserved)
        check(claimed.size <= reserved) { "Claimed ${claimed.size} rows with $reserved reserved dispatch slots" }
        claims = claimed
    } finally {
        releaseDispatchSlots(count = reserved - claims.size)
    }
    if (claims.isNotEmpty()) batchPendingMessages(claims = claims)
    return claims
}

data class OutboxClaim(
    val row: OutboxMessage,
    val attempt: Int,
)

fun MessageOutboxRepository.claim(row: OutboxMessage, now: LocalDateTime): OutboxClaim? =
    if (claimPending(eventId = row.eventId, attemptCount = row.attemptCount, now = now) == 1) {
        OutboxClaim(row = row, attempt = row.attemptCount + 1)
    } else {
        null
    }

fun MessageOutboxRepository.reclaim(row: OutboxMessage, olderThan: LocalDateTime, now: LocalDateTime): OutboxClaim? =
    if (reclaimStuck(eventId = row.eventId, attemptCount = row.attemptCount, olderThan = olderThan, now = now) == 1) {
        OutboxClaim(row = row, attempt = row.attemptCount + 1)
    } else {
        null
    }
