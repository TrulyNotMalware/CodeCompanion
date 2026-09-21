package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration

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
    fun pollPending() {
        recoverStuckInProgress()
        claimAndDispatch()
    }

    private fun recoverStuckInProgress() {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minus(stuckInProgressThreshold)
        val stuck = outboxRepository.findStuckInProgress(olderThan = cutoff, limit = batchSize)
        if (stuck.isNotEmpty()) {
            messageRelayService.batchPendingMessages(pendingMessages = stuck)
        }
    }

    // One batch per tick (no inner loop) so the scheduler thread doesn't starve other work.
    private fun claimAndDispatch() {
        val candidates = outboxRepository.findPendingMessages(limit = batchSize, offset = 0)
        if (candidates.isEmpty()) return
        val claimedCount = outboxRepository.claimPending(eventIds = candidates.map { it.eventId })
        if (claimedCount <= 0) return
        val toDispatch = candidates.take(n = claimedCount)
        messageRelayService.batchPendingMessages(pendingMessages = toDispatch)
    }
}
