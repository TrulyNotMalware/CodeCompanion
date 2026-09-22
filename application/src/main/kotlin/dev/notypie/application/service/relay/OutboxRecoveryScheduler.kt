package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

// Runs in both relay modes. CDC has no second look at a row: a claim that never reached its status
// update (crash, exhausted update retries, DLT) stays IN_PROGRESS, and a record lost to the DLT stays
// PENDING, with no new change event for either. Both are swept here with the same per-row CAS the
// poller uses, so two instances cannot re-dispatch the same row.
@Component
class OutboxRecoveryScheduler(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: MessageRelayService,
    appConfig: AppConfig,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val batchSize: Int = appConfig.outbox.polling.batchSize
    private val threshold: Duration = Duration.ofSeconds(appConfig.outbox.polling.stuckInProgressSeconds)
    private val giveUpAfter: Duration = Duration.ofHours(appConfig.outbox.polling.giveUpAfterHours)

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    fun recover() {
        runCatching { recoverOnce() }.onFailure { log.error(it) { "Outbox recovery sweep failed" } }
    }

    // A poison row (dispatch keeps failing before its status update lands) would otherwise be re-sent every
    // sweep forever, and each reclaim resets the age the health probe reports; created_at is the one
    // timestamp the sweep never touches, so it bounds the retry loop.
    fun recoverOnce(): Int {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minus(threshold)
        val giveUpBefore = now.minus(giveUpAfter)
        val (abandoned, stuck) =
            outboxRepository
                .findStuckInProgress(olderThan = cutoff, limit = batchSize)
                .partition { it.createdAt < giveUpBefore }
        abandoned.forEach { row ->
            if (outboxRepository.abandonStuck(eventId = row.eventId) == 1) {
                log.error { "Outbox row eventId=${row.eventId} abandoned after ${giveUpAfter.toHours()}h IN_PROGRESS" }
            }
        }
        val reclaimed = stuck.filter { outboxRepository.reclaimStuck(eventId = it.eventId, olderThan = cutoff) == 1 }
        val stale =
            outboxRepository
                .findStalePending(olderThan = cutoff, limit = batchSize)
                .filter { outboxRepository.claimPending(eventIds = listOf(it.eventId)) == 1 }
        val rows = reclaimed + stale
        if (rows.isNotEmpty()) {
            log.warn { "Outbox recovery re-dispatching ${reclaimed.size} stuck and ${stale.size} stale rows" }
            messageRelayService.batchPendingMessages(pendingMessages = rows)
        }
        return rows.size
    }
}
