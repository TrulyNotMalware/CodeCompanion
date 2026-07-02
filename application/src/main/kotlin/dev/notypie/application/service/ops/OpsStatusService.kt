package dev.notypie.application.service.ops

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Renders an outbox-status report in response to `@bot status` mentions. Reads the same
 * counters that drive [dev.notypie.application.health.OutboxHealthIndicator] so the chat
 * reply and the actuator health endpoint never disagree about lag/in-flight numbers.
 *
 * The text is posted as a regular channel message (not ephemeral): operators may want to
 * scroll back through historical status reports, and the only invocation path is an
 * intentional `@bot status` mention.
 */
@Service
class OpsStatusService(
    private val outboxRepository: MessageOutboxRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) {
    private val stuckThresholdSeconds: Long = appConfig.outbox.health.stuckThresholdSeconds

    @EventListener
    fun handleStatusReport(event: StatusReportRequestEvent) {
        val payload = event.payload
        val text =
            runCatching { renderReport() }
                .getOrElse { exception ->
                    log.error(exception) {
                        "Failed to render outbox status report idempotencyKey=${event.idempotencyKey}"
                    }
                    "Failed to read outbox status. Check application logs."
                }

        outboundStager
            .stage(
                message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = payload.responseBasicInfo.channel),
                        content =
                            MessageContent.Text(
                                headline = "CodeCompanion — outbox status",
                                markdown = text,
                            ),
                        detailType = CommandDetailType.STATUS_REPORT,
                    ),
                basicInfo = payload.responseBasicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    private fun renderReport(): String {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minusSeconds(stuckThresholdSeconds)

        val pendingCount = outboxRepository.countPending()
        val stuckPendingCount = outboxRepository.countPendingOlderThan(threshold = cutoff)
        val oldestPending = outboxRepository.findOldestPendingCreatedAt()
        val oldestPendingAgeSeconds =
            oldestPending?.let { Duration.between(it, now).seconds.coerceAtLeast(0L) } ?: 0L

        val inFlightCount = outboxRepository.countInProgress()
        val stuckInFlightCount = outboxRepository.countInProgressOlderThan(threshold = cutoff)
        val oldestInFlight = outboxRepository.findOldestInProgressUpdatedAt()
        val oldestInFlightAgeSeconds =
            oldestInFlight?.let { Duration.between(it, now).seconds.coerceAtLeast(0L) } ?: 0L

        val healthy = stuckPendingCount == 0L && stuckInFlightCount == 0L
        val healthLine = if (healthy) "*Health:* :large_green_circle: UP" else "*Health:* :red_circle: DOWN"

        return buildString {
            appendLine("• *Pending:* $pendingCount (oldest ${oldestPendingAgeSeconds}s ago, stuck $stuckPendingCount)")
            appendLine(
                "• *In-flight:* $inFlightCount (oldest ${oldestInFlightAgeSeconds}s ago, stuck $stuckInFlightCount)",
            )
            appendLine("• Stuck threshold: ${stuckThresholdSeconds}s")
            append(healthLine)
        }
    }
}
