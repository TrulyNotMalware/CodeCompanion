package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

// Stuck/stale recovery lives in OutboxRecoveryScheduler, shared with CDC mode.
class PollingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl,
    appConfig: AppConfig,
    private val clock: Clock,
) : MessageProcessor {
    private val batchSize: Int = appConfig.outbox.polling.batchSize
    private val ticking = AtomicBoolean(false)

    // fixedRate ticks can overlap (a SimpleAsyncTaskScheduler starts each on a new thread), so a tick that finds
    // the previous one still claiming is skipped rather than adding another claim lane.
    @Scheduled(fixedRate = 5000)
    fun pollPending() {
        if (!ticking.compareAndSet(false, true)) return
        try {
            claimAndDispatch()
        } finally {
            ticking.set(false)
        }
    }

    // One batch per tick (no inner loop) so the scheduler thread doesn't starve other work. Claims only as many rows
    // as it reserved relay slots for: a PENDING row left unclaimed is picked up by a later tick, a claimed one would
    // wait 300 s, and the sweep may be reserving on another scheduler thread at the same time.
    private fun claimAndDispatch() {
        val now = LocalDateTime.now(clock)
        messageRelayService.claimWithReservedSlots(wanted = batchSize) { slots ->
            outboxRepository
                .findPendingMessages(limit = slots)
                .mapNotNull { outboxRepository.claim(row = it, now = now) }
        }
    }
}
