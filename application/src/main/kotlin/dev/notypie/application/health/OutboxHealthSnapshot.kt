package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.relay.RECOVERY_SWEEP_PERIOD_MILLIS
import dev.notypie.repository.outbox.MessageOutboxRepository
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

// The one outbox verdict: OutboxHealthIndicator and OpsStatusService (@bot status, MCP get_status) both read it.
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
) {
    val healthy: Boolean
        get() = stuckPendingCount == 0L && stuckInFlightCount == 0L && retryingCount == 0L
}

fun MessageOutboxRepository.readOutboxHealth(clock: Clock, health: AppConfig.Outbox.Health): OutboxHealthSnapshot {
    val now = clock.instant().atZone(clock.zone).toLocalDateTime()
    val cutoff = now.minusSeconds(health.stuckThresholdSeconds)
    return OutboxHealthSnapshot(
        pendingCount = countPending(),
        stuckPendingCount = countPendingOlderThan(threshold = cutoff),
        oldestPendingAgeSeconds = ageSeconds(at = findOldestPendingCreatedAt(), now = now),
        inFlightCount = countInProgress(),
        // The sweep gets one period to pick up a row that just became eligible.
        stuckInFlightCount =
            countInProgressOlderThan(threshold = cutoff.minus(Duration.ofMillis(RECOVERY_SWEEP_PERIOD_MILLIS))),
        oldestInFlightAgeSeconds = ageSeconds(at = findOldestInProgressUpdatedAt(), now = now),
        retryingCount = countInProgressWithSendsAtLeast(sends = health.retryingSendThreshold),
        stuckThresholdSeconds = health.stuckThresholdSeconds,
        retryingSendThreshold = health.retryingSendThreshold,
    )
}

private fun ageSeconds(at: LocalDateTime?, now: LocalDateTime): Long =
    at?.let { Duration.between(it, now).seconds.coerceAtLeast(0L) } ?: 0L
