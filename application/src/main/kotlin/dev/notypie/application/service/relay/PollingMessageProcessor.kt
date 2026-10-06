package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.LocalDateTime

// Stuck/stale recovery lives in OutboxRecoveryScheduler, shared with CDC mode.
class PollingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl,
    appConfig: AppConfig,
    private val clock: Clock,
) : MessageProcessor {
    private val batchSize: Int = appConfig.outbox.polling.batchSize

    @Scheduled(fixedRate = 5000)
    fun pollPending() {
        claimAndDispatch()
    }

    // One batch per tick (no inner loop) so the scheduler thread doesn't starve other work.
    private fun claimAndDispatch() {
        val now = LocalDateTime.now(clock)
        messageRelayService.claimWithReservedSlots(wanted = batchSize) { slots ->
            outboxRepository
                .findPendingMessages(limit = slots)
                .mapNotNull { outboxRepository.claim(row = it, now = now) }
        }
    }
}
