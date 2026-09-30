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

    // One batch per tick (no inner loop) so the scheduler thread doesn't starve other work. Claims only what the
    // relay can queue: a PENDING row left unclaimed is picked up by a later tick, a claimed one would wait 300 s.
    private fun claimAndDispatch() {
        val slots = messageRelayService.freeDispatchSlots()
        if (slots <= 0) return
        val now = LocalDateTime.now(clock)
        val claims =
            outboxRepository
                .findPendingMessages(limit = minOf(batchSize, slots))
                .mapNotNull { outboxRepository.claim(row = it, now = now) }
        if (claims.isEmpty()) return
        messageRelayService.batchPendingMessages(claims = claims)
    }
}
