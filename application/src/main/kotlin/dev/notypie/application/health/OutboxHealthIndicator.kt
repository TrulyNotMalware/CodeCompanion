package dev.notypie.application.health

import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Reports the health of the outbox relay so that operators can see when
 * messages are accumulating or stalling mid-dispatch.
 *
 * Health is `DOWN` whenever at least one PENDING *or* IN_PROGRESS row is older than the
 * configured stuck threshold; otherwise `UP`. The two states tell different stories:
 *
 *   - PENDING stuck → the poller is failing to pick up rows (DB lag, scheduler stalled,
 *     pod down). Look at scheduling.
 *   - IN_PROGRESS stuck → the poller claimed a row and crashed before publishing
 *     SUCCESS/FAILURE. Look at dispatch failures and crash logs.
 *
 * Detail keys are stable so dashboards/alerts can pin against them:
 *
 * - `pendingCount` / `stuckPendingCount` / `oldestPendingAgeSeconds`
 * - `inFlightCount` / `stuckInFlightCount` / `oldestInFlightAgeSeconds`
 * - `stuckThresholdSeconds` — the threshold both states share
 *
 * The `stuckCount` key is preserved as an alias of `stuckPendingCount` so existing
 * dashboards / alert queries do not break in this PR.
 */
@Component
class OutboxHealthIndicator(
    private val outboxRepository: MessageOutboxRepository,
    private val clock: Clock,
    @Value("\${outbox.health.stuck-threshold-seconds:300}")
    stuckThresholdSeconds: Long,
) : HealthIndicator {
    private val stuckThreshold: Duration = Duration.ofSeconds(stuckThresholdSeconds)

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

    private fun ageSeconds(at: java.time.LocalDateTime?, now: java.time.LocalDateTime): Long =
        if (at == null) 0L else Duration.between(at, now).seconds.coerceAtLeast(0L)
}
