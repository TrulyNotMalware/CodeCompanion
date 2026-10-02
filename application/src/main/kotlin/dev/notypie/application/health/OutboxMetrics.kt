package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.relay.AccessBlockedTracker
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.TimeGauge
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.TimeUnit

const val OUTBOX_MESSAGES_METRIC = "outbox.messages"
const val OUTBOX_PENDING_OLDEST_AGE_METRIC = "outbox.pending.oldest.age"
const val OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC = "outbox.in.progress.oldest.claim.age"
const val OUTBOX_RETRYING_METRIC = "outbox.retrying.messages"
const val OUTBOX_ACCESS_BLOCKED_METRIC = "outbox.access.blocked"

private val SNAPSHOT_REUSE_NANOS: Long = TimeUnit.SECONDS.toNanos(1L)

@Component
class OutboxMetrics(
    private val outboxRepository: MessageOutboxRepository,
    private val clock: Clock,
    private val accessBlockedTracker: AccessBlockedTracker,
    appConfig: AppConfig,
    meterRegistry: MeterRegistry,
) {
    private val healthConfig: AppConfig.Outbox.Health = appConfig.outbox.health

    @Volatile
    private var last: Pair<Long, OutboxHealthSnapshot>? = null

    init {
        Gauge
            .builder(OUTBOX_MESSAGES_METRIC) { snapshot().pendingCount }
            .tag("status", "pending")
            .description("Outbox rows waiting for a relay claim")
            .register(meterRegistry)
        Gauge
            .builder(OUTBOX_MESSAGES_METRIC) { snapshot().inFlightCount }
            .tag("status", "in_progress")
            .description("Outbox rows claimed by a relay and not yet terminal")
            .register(meterRegistry)
        TimeGauge
            .builder(OUTBOX_PENDING_OLDEST_AGE_METRIC, { snapshot().oldestPendingAgeSeconds }, TimeUnit.SECONDS)
            .description("Time since the oldest PENDING row was created; 0 when none is pending")
            .register(meterRegistry)
        TimeGauge
            .builder(
                OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC,
                { snapshot().oldestInFlightAgeSeconds },
                TimeUnit.SECONDS,
            ).description("Time since the oldest IN_PROGRESS row was claimed or renewed; 0 when none is in flight")
            .register(meterRegistry)
        Gauge
            .builder(OUTBOX_RETRYING_METRIC) { snapshot().retryingCount }
            .description(
                "IN_PROGRESS rows already sent at least slack.app.outbox.health.retrying-send-threshold times",
            ).register(meterRegistry)
        Gauge
            .builder(OUTBOX_ACCESS_BLOCKED_METRIC) { if (snapshot().accessBlocked) 1.0 else 0.0 }
            .description(
                "1 while this replica held a row for a Slack access error within " +
                    "slack.app.outbox.health.access-blocked-window-seconds, else 0",
            ).register(meterRegistry)
    }

    private fun snapshot(): OutboxHealthSnapshot {
        val now = System.nanoTime()
        last?.let { (readAt, snapshot) -> if (now - readAt < SNAPSHOT_REUSE_NANOS) return snapshot }
        return outboxRepository
            .readOutboxHealth(clock = clock, health = healthConfig, accessBlockedTracker = accessBlockedTracker)
            .also { last = now to it }
    }
}
