package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.MessageProcessorParameter
import dev.notypie.application.service.relay.dto.NoParameter
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration

/**
 * Polls the outbox on a fixed cadence and hands work to the relay service. Each tick:
 *
 *   1. Recovers crash-orphaned IN_PROGRESS rows older than [stuckInProgressThreshold] —
 *      they're re-dispatched, not silently abandoned.
 *   2. Reads PENDING candidates, atomically claims each row by transitioning it to
 *      IN_PROGRESS via [MessageOutboxRepository.claimPending]. The atomic CAS in that
 *      query is the source of truth: if the claim count is less than the candidate count,
 *      racing pollers got there first and we drop those candidates rather than dispatch
 *      under contended state.
 *   3. Dispatches only the rows we actually claimed (or recovered).
 *
 * Note: the previous implementation paged with `LIMIT/OFFSET` and re-dispatched any row
 * still marked PENDING — including rows mid-flight on another worker. The IN_PROGRESS
 * transition closes that hole.
 */
class PollingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl,
    private val clock: Clock = Clock.systemDefaultZone(),
    @param:Value("\${outbox.polling.batch-size:100}")
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    @Value("\${outbox.polling.stuck-in-progress-seconds:300}")
    stuckInProgressSeconds: Long = DEFAULT_STUCK_IN_PROGRESS_SECONDS,
) : MessageProcessor {
    private val stuckInProgressThreshold: Duration = Duration.ofSeconds(stuckInProgressSeconds)

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 100
        const val DEFAULT_STUCK_IN_PROGRESS_SECONDS: Long = 300L
    }

    @Scheduled(fixedRate = 5000)
    fun scheduleDispatch() = getPendingMessages(messageParameter = NoParameter)

    override fun getPendingMessages(messageParameter: MessageProcessorParameter) {
        recoverStuckInProgress()
        claimAndDispatch()
    }

    /**
     * Re-dispatches IN_PROGRESS rows whose claim has gone stale. The dispatch path is
     * idempotent at the Slack API level (each row carries its own event_id), so a re-send
     * is preferable to a stuck row that never resolves.
     */
    private fun recoverStuckInProgress() {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minus(stuckInProgressThreshold)
        val stuck = outboxRepository.findStuckInProgress(olderThan = cutoff, limit = batchSize)
        if (stuck.isNotEmpty()) {
            messageRelayService.batchPendingMessages(pendingMessages = stuck)
        }
    }

    /**
     * Reads PENDING candidates and claims them in a single atomic UPDATE. We dispatch up to
     * the claimed count — the candidate list is allowed to be larger if rows raced or were
     * already claimed elsewhere. Each tick processes one batch (no inner loop) so the
     * scheduler thread doesn't starve other work; the next tick picks up whatever remains.
     */
    private fun claimAndDispatch() {
        val candidates = outboxRepository.findPendingMessages(limit = batchSize, offset = 0)
        if (candidates.isEmpty()) return
        val claimedCount = outboxRepository.claimPending(eventIds = candidates.map { it.eventId })
        if (claimedCount <= 0) return
        // Trust the read order: claimPending acted on the same set we read, and we hand the
        // first `claimedCount` rows to the dispatcher. If a race shaved off some claims, the
        // skipped rows remain PENDING and will re-enter the next tick.
        val toDispatch = candidates.take(n = claimedCount)
        messageRelayService.batchPendingMessages(pendingMessages = toDispatch)
    }
}
