package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.time.LocalDateTime

interface MessageRelayService {
    // Claims batchPendingMessages can take right now; callers claim no more, so a claimed row is rarely left waiting.
    fun freeDispatchSlots(): Int

    fun batchPendingMessages(claims: List<OutboxClaim>)

    fun dispatchClaimed(claim: OutboxClaim)
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
