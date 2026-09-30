package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class OutboxHealthIndicator(
    private val outboxRepository: MessageOutboxRepository,
    private val clock: Clock,
    appConfig: AppConfig = AppConfig(),
) : HealthIndicator {
    private val healthConfig: AppConfig.Outbox.Health = appConfig.outbox.health

    override fun health(): Health {
        val snapshot = outboxRepository.readOutboxHealth(clock = clock, health = healthConfig)
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
            .build()
    }
}
