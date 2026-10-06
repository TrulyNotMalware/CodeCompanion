package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.time.LocalDateTime

interface MessageRelayService {
    fun reserveDispatchSlots(wanted: Int): Int

    fun releaseDispatchSlots(count: Int)

    fun batchPendingMessages(claims: List<OutboxClaim>)

    fun dispatchClaimed(claim: OutboxClaim)
}

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
