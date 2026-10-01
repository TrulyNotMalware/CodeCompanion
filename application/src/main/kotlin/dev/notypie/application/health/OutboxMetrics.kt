package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.TimeGauge
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

const val OUTBOX_MESSAGES_METRIC = "outbox.messages"
const val OUTBOX_PENDING_OLDEST_AGE_METRIC = "outbox.pending.oldest.age"
const val OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC = "outbox.in.progress.oldest.claim.age"
const val OUTBOX_RETRYING_METRIC = "outbox.retrying.messages"

@Component
class OutboxMetrics(
    private val outboxRepository: MessageOutboxRepository,
    private val clock: Clock,
    appConfig: AppConfig,
    meterRegistry: MeterRegistry,
) {
    private val retryingSendThreshold: Int = appConfig.outbox.health.retryingSendThreshold

    init {
        Gauge
            .builder(OUTBOX_MESSAGES_METRIC) { outboxRepository.countPending() }
            .tag("status", "pending")
            .description("Outbox rows waiting for a relay claim")
            .register(meterRegistry)
        Gauge
            .builder(OUTBOX_MESSAGES_METRIC) { outboxRepository.countInProgress() }
            .tag("status", "in_progress")
            .description("Outbox rows claimed by a relay and not yet terminal")
            .register(meterRegistry)
        TimeGauge
            .builder(
                OUTBOX_PENDING_OLDEST_AGE_METRIC,
                { ageSeconds(at = outboxRepository.findOldestPendingCreatedAt()) },
                TimeUnit.SECONDS,
            ).description("Time since the oldest PENDING row was created; 0 when none is pending")
            .register(meterRegistry)
        TimeGauge
            .builder(
                OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC,
                { ageSeconds(at = outboxRepository.findOldestInProgressUpdatedAt()) },
                TimeUnit.SECONDS,
            ).description("Time since the oldest IN_PROGRESS row was claimed or renewed; 0 when none is in flight")
            .register(meterRegistry)
        Gauge
            .builder(OUTBOX_RETRYING_METRIC) {
                outboxRepository.countInProgressWithSendsAtLeast(sends = retryingSendThreshold)
            }.description(
                "IN_PROGRESS rows already sent at least slack.app.outbox.health.retrying-send-threshold times",
            ).register(meterRegistry)
    }

    private fun ageSeconds(at: LocalDateTime?): Long =
        at?.let { Duration.between(it, LocalDateTime.now(clock)).seconds.coerceAtLeast(0L) } ?: 0L
}
