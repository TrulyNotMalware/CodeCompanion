package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

const val RECOVERY_SWEEP_PERIOD_MILLIS = 60_000L

@Component
class OutboxRecoveryScheduler(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: MessageRelayService,
    appConfig: AppConfig,
    private val clock: Clock,
) {
    private val batchSize: Int = appConfig.outbox.polling.batchSize
    private val threshold: Duration = Duration.ofSeconds(appConfig.outbox.polling.stuckInProgressSeconds)
    private val giveUpAfter: Duration = Duration.ofHours(appConfig.outbox.polling.giveUpAfterHours)
    private val maxSends: Int = appConfig.outbox.polling.maxSends

    @Scheduled(fixedDelay = RECOVERY_SWEEP_PERIOD_MILLIS, initialDelay = RECOVERY_SWEEP_PERIOD_MILLIS)
    fun recover() {
        runCatching { recoverOnce() }.onFailure { log.error(it) { "Outbox recovery sweep failed" } }
    }

    fun recoverOnce(): Int {
        val now = LocalDateTime.now(clock)
        val cutoff = now.minus(threshold)
        val giveUpBefore = now.minus(giveUpAfter)
        val (abandoned, stuck) =
            outboxRepository
                .findStuckInProgress(olderThan = cutoff, limit = batchSize)
                .partition { it.sendCount >= maxSends || it.createdAt < giveUpBefore }
        abandoned.forEach { row ->
            val abandonedNow =
                outboxRepository.abandonStuck(
                    eventId = row.eventId,
                    attemptCount = row.attemptCount,
                    olderThan = cutoff,
                    now = now,
                ) == 1
            if (abandonedNow) {
                log.error {
                    "Outbox row eventId=${row.eventId} idempotencyKey=${row.idempotencyKey} abandoned to FAILURE " +
                        "after ${row.sendCount} sends and ${row.attemptCount} claims, created at ${row.createdAt}"
                }
            }
        }
        // Only as many claims as the relay can queue; the rest stay eligible for the next sweep, unclaimed.
        val slots = messageRelayService.freeDispatchSlots().coerceAtLeast(0)
        val reclaimed =
            stuck
                .asSequence()
                .mapNotNull { outboxRepository.reclaim(row = it, olderThan = cutoff, now = now) }
                .take(slots)
                .toList()
        // A PENDING row past the give-up window is failed unclaimed, even when the relay has no free slot.
        val (expired, live) =
            outboxRepository
                .findStalePending(olderThan = cutoff, limit = batchSize)
                .partition { it.createdAt < giveUpBefore }
        expired.forEach { row ->
            val abandonedNow =
                outboxRepository.abandonPending(eventId = row.eventId, attemptCount = row.attemptCount, now = now) == 1
            if (abandonedNow) {
                log.error {
                    "Outbox row eventId=${row.eventId} idempotencyKey=${row.idempotencyKey} abandoned to FAILURE " +
                        "unsent: still PENDING past the give-up window, created at ${row.createdAt}"
                }
            }
        }
        val staleSlots = slots - reclaimed.size
        val stale =
            live
                .asSequence()
                .mapNotNull { outboxRepository.claim(row = it, now = now) }
                .take(staleSlots)
                .toList()
        val claims = reclaimed + stale
        if (claims.isNotEmpty()) {
            log.warn { "Outbox recovery re-dispatching ${reclaimed.size} stuck and ${stale.size} stale rows" }
            messageRelayService.batchPendingMessages(claims = claims)
        }
        return claims.size
    }
}
