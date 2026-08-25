package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration

/**
 * Polls the outbox on a fixed cadence. Each tick recovers crash-orphaned IN_PROGRESS rows past the
 * stuck threshold, then reads PENDING candidates and atomically claims them (PENDING→IN_PROGRESS)
 * before dispatching only the rows it actually claimed. The CAS in [MessageOutboxRepository.claimPending]
 * is the source of truth, so racing pollers can't double-dispatch.
 */
class PollingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl,
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) : MessageProcessor {
    private val batchSize: Int = appConfig.outbox.polling.batchSize
    private val stuckInProgressThreshold: Duration =
        Duration.ofSeconds(appConfig.outbox.polling.stuckInProgressSeconds)

    @Scheduled(fixedRate = 5000)
    fun scheduleDispatch() = getPendingMessages(messageParameter = NoParameter)

    override fun getPendingMessages(messageParameter: MessageProcessorParameter) {
        recoverStuckInProgress()
        claimAndDispatch()
    }

    // Re-dispatches IN_PROGRESS rows whose claim went stale. Dispatch is idempotent (each row carries
    // its own event_id), so a re-send beats a stuck row that never resolves.
    private fun recoverStuckInProgress() {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minus(stuckInProgressThreshold)
        val stuck = outboxRepository.findStuckInProgress(olderThan = cutoff, limit = batchSize)
        if (stuck.isNotEmpty()) {
            messageRelayService.batchPendingMessages(pendingMessages = stuck)
        }
    }

    // Reads PENDING candidates and claims them in one atomic UPDATE, dispatching up to the claimed
    // count. One batch per tick (no inner loop) so the scheduler thread doesn't starve other work.
    private fun claimAndDispatch() {
        val candidates = outboxRepository.findPendingMessages(limit = batchSize, offset = 0)
        if (candidates.isEmpty()) return
        val claimedCount = outboxRepository.claimPending(eventIds = candidates.map { it.eventId })
        if (claimedCount <= 0) return
        // claimPending acted on the same set we read, so hand it the first claimedCount rows; any
        // shaved off by a race stay PENDING for the next tick.
        val toDispatch = candidates.take(n = claimedCount)
        messageRelayService.batchPendingMessages(pendingMessages = toDispatch)
    }
}
