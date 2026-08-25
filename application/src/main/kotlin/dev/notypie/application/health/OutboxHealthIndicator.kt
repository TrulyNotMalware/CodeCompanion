package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * Reports outbox-relay health: DOWN when any PENDING or IN_PROGRESS row is older than the configured
 * stuck threshold, else UP. PENDING-stuck points at the poller (lag/stall), IN_PROGRESS-stuck at a
 * dispatch that claimed a row and crashed. Detail keys are stable for dashboards; `stuckCount` is a
 * legacy alias of `stuckPendingCount`.
 */
@Component
class OutboxHealthIndicator(
    private val outboxRepository: MessageOutboxRepository,
    private val clock: Clock,
    appConfig: AppConfig = AppConfig(),
) : HealthIndicator {
    private val stuckThreshold: Duration = Duration.ofSeconds(appConfig.outbox.health.stuckThresholdSeconds)

    override fun health(): Health {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minus(stuckThreshold)

        val pendingCount = outboxRepository.countPending()
        val stuckPendingCount = outboxRepository.countPendingOlderThan(threshold = cutoff)
        val oldestPending = outboxRepository.findOldestPendingCreatedAt()
        val oldestPendingAgeSeconds = ageSeconds(at = oldestPending, now = now)

        val inFlightCount = outboxRepository.countInProgress()
        val stuckInFlightCount = outboxRepository.countInProgressOlderThan(threshold = cutoff)
        val oldestInFlight = outboxRepository.findOldestInProgressUpdatedAt()
        val oldestInFlightAgeSeconds = ageSeconds(at = oldestInFlight, now = now)

        val builder =
            if (stuckPendingCount > 0L || stuckInFlightCount > 0L) {
                Health.down()
            } else {
                Health.up()
            }

        return builder
            .withDetail("pendingCount", pendingCount)
            .withDetail("stuckPendingCount", stuckPendingCount)
            // alias preserved so existing dashboards keep working — drop in a follow-up PR
            .withDetail("stuckCount", stuckPendingCount)
            .withDetail("oldestPendingAgeSeconds", oldestPendingAgeSeconds)
            .withDetail("inFlightCount", inFlightCount)
            .withDetail("stuckInFlightCount", stuckInFlightCount)
            .withDetail("oldestInFlightAgeSeconds", oldestInFlightAgeSeconds)
            .withDetail("stuckThresholdSeconds", stuckThreshold.seconds)
            .build()
    }

    private fun ageSeconds(at: LocalDateTime?, now: LocalDateTime): Long =
        at?.let { Duration.between(it, now).seconds.coerceAtLeast(0L) } ?: 0L
}
