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
 * messages are accumulating without being dispatched to Slack.
 *
 * Health is `DOWN` while at least one PENDING row is older than the
 * configured stuck threshold; otherwise `UP`. Detail keys are stable so
 * dashboards/alerts can pin against them:
 *
 * - `pendingCount` — total PENDING rows right now
 * - `stuckCount` — PENDING rows older than [stuckThreshold]
 * - `oldestPendingAgeSeconds` — age of the oldest PENDING row, or 0 when empty
 * - `stuckThresholdSeconds` — the configured threshold for context
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
        val stuckCount = outboxRepository.countPendingOlderThan(threshold = cutoff)
        val oldestPending = outboxRepository.findOldestPendingCreatedAt()
        val oldestPendingAgeSeconds =
            if (oldestPending == null) {
                0L
            } else {
                Duration.between(oldestPending, now).seconds.coerceAtLeast(0L)
            }

        val builder =
            if (stuckCount > 0L) {
                Health.down()
            } else {
                Health.up()
            }

        return builder
            .withDetail("pendingCount", pendingCount)
            .withDetail("stuckCount", stuckCount)
            .withDetail("oldestPendingAgeSeconds", oldestPendingAgeSeconds)
            .withDetail("stuckThresholdSeconds", stuckThreshold.seconds)
            .build()
    }
}
