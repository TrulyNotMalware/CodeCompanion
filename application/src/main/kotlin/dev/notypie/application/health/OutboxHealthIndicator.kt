package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.relay.AccessBlockedTracker
import dev.notypie.application.service.relay.RECOVERY_SWEEP_PERIOD_MILLIS
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

data class OutboxHealthSnapshot(
    val pendingCount: Long,
    val stuckPendingCount: Long,
    val oldestPendingAgeSeconds: Long,
    val inFlightCount: Long,
    val stuckInFlightCount: Long,
    val oldestInFlightAgeSeconds: Long,
    val retryingCount: Long,
    val stuckThresholdSeconds: Long,
    val retryingSendThreshold: Int,
    val lastAccessBlockedAt: Instant?,
    val accessBlockedWindowSeconds: Long,
    val accessBlocked: Boolean,
) {
    val healthy: Boolean
        get() = stuckPendingCount == 0L && stuckInFlightCount == 0L && retryingCount == 0L && !accessBlocked
}

fun MessageOutboxRepository.readOutboxHealth(
    clock: Clock,
    health: AppConfig.Outbox.Health,
    accessBlockedTracker: AccessBlockedTracker,
): OutboxHealthSnapshot {
    val instant = clock.instant()
    val now = instant.atZone(clock.zone).toLocalDateTime()
    val cutoff = now.minusSeconds(health.stuckThresholdSeconds)
    val lastAccessBlockedAt = accessBlockedTracker.lastBlockedAt()
    return OutboxHealthSnapshot(
        pendingCount = countPending(),
        stuckPendingCount = countPendingOlderThan(threshold = cutoff),
        oldestPendingAgeSeconds = ageSeconds(at = findOldestPendingCreatedAt(), now = now),
        inFlightCount = countInProgress(),
        stuckInFlightCount =
            countInProgressOlderThan(threshold = cutoff.minus(Duration.ofMillis(RECOVERY_SWEEP_PERIOD_MILLIS))),
        oldestInFlightAgeSeconds = ageSeconds(at = findOldestInProgressUpdatedAt(), now = now),
        retryingCount = countInProgressWithSendsAtLeast(sends = health.retryingSendThreshold),
        stuckThresholdSeconds = health.stuckThresholdSeconds,
        retryingSendThreshold = health.retryingSendThreshold,
        lastAccessBlockedAt = lastAccessBlockedAt,
        accessBlockedWindowSeconds = health.accessBlockedWindowSeconds,
        accessBlocked =
            lastAccessBlockedAt != null &&
                Duration.between(lastAccessBlockedAt, instant).seconds < health.accessBlockedWindowSeconds,
    )
}

private fun ageSeconds(at: LocalDateTime?, now: LocalDateTime): Long =
    at?.let { Duration.between(it, now).seconds.coerceAtLeast(0L) } ?: 0L

@Component
class OutboxHealthIndicator(
    private val outboxRepository: MessageOutboxRepository,
    private val clock: Clock,
    private val accessBlockedTracker: AccessBlockedTracker,
    appConfig: AppConfig,
) : HealthIndicator {
    private val healthConfig: AppConfig.Outbox.Health = appConfig.outbox.health

    override fun health(): Health {
        val snapshot =
            outboxRepository.readOutboxHealth(
                clock = clock,
                health = healthConfig,
                accessBlockedTracker = accessBlockedTracker,
            )
        val builder = if (snapshot.healthy) Health.up() else Health.down()

        return builder
            .withDetail("pendingCount", snapshot.pendingCount)
            .withDetail("stuckPendingCount", snapshot.stuckPendingCount)
            .withDetail("stuckCount", snapshot.stuckPendingCount)
            .withDetail("oldestPendingAgeSeconds", snapshot.oldestPendingAgeSeconds)
            .withDetail("inFlightCount", snapshot.inFlightCount)
            .withDetail("stuckInFlightCount", snapshot.stuckInFlightCount)
            .withDetail("oldestInFlightAgeSeconds", snapshot.oldestInFlightAgeSeconds)
            .withDetail("stuckThresholdSeconds", snapshot.stuckThresholdSeconds)
            .withDetail("retryingCount", snapshot.retryingCount)
            .withDetail("retryingSendThreshold", snapshot.retryingSendThreshold)
            .withDetail("accessBlocked", snapshot.accessBlocked)
            .withDetail("lastAccessBlockedAt", snapshot.lastAccessBlockedAt?.toString() ?: "never")
            .withDetail("accessBlockedWindowSeconds", snapshot.accessBlockedWindowSeconds)
            .build()
    }
}
