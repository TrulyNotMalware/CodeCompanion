package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled

// Stuck/stale recovery lives in OutboxRecoveryScheduler, shared with CDC mode.
class PollingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl,
    appConfig: AppConfig,
) : MessageProcessor {
    private val batchSize: Int = appConfig.outbox.polling.batchSize

    @Scheduled(fixedRate = 5000)
    fun pollPending() {
        claimAndDispatch()
    }

    // One batch per tick (no inner loop) so the scheduler thread doesn't starve other work. Rows are claimed
    // one by one: a bulk UPDATE only reports how many rows it won, not which.
    private fun claimAndDispatch() {
        val claimed =
            outboxRepository
                .findPendingMessages(limit = batchSize)
                .filter { outboxRepository.claimPending(eventIds = listOf(it.eventId)) == 1 }
        if (claimed.isEmpty()) return
        messageRelayService.batchPendingMessages(pendingMessages = claimed)
    }
}
