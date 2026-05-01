package dev.notypie.application.service.ops

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
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
    private val slackEventBuilder: SlackApiEventConstructor,
    private val eventPublisher: EventPublisher,
    private val clock: Clock = Clock.systemDefaultZone(),
    @param:Value("\${outbox.health.stuck-threshold-seconds:300}")
    private val stuckThresholdSeconds: Long = DEFAULT_STUCK_THRESHOLD_SECONDS,
) {
    companion object {
        const val DEFAULT_STUCK_THRESHOLD_SECONDS: Long = 300L
    }

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

        val message =
            slackEventBuilder.simpleTextRequest(
                commandDetailType = CommandDetailType.STATUS_REPORT,
                headLineText = "CodeCompanion — outbox status",
                commandBasicInfo = payload.responseBasicInfo,
                simpleString = text,
            )
        val queue = DefaultEventQueue<CommandEvent<EventPayload>>()
        @Suppress("UNCHECKED_CAST")
        queue.offer(event = message as CommandEvent<EventPayload>)
        eventPublisher.publishEvent(events = queue)
    }

    private fun renderReport(): String {
        val now = clock.instant().atZone(clock.zone).toLocalDateTime()
        val cutoff = now.minusSeconds(stuckThresholdSeconds)

        val pendingCount = outboxRepository.countPending()
        val stuckPendingCount = outboxRepository.countPendingOlderThan(threshold = cutoff)
        val oldestPending = outboxRepository.findOldestPendingCreatedAt()
        val oldestPendingAgeSeconds =
            if (oldestPending == null) 0L else Duration.between(oldestPending, now).seconds.coerceAtLeast(0L)

        val inFlightCount = outboxRepository.countInProgress()
        val stuckInFlightCount = outboxRepository.countInProgressOlderThan(threshold = cutoff)
        val oldestInFlight = outboxRepository.findOldestInProgressUpdatedAt()
        val oldestInFlightAgeSeconds =
            if (oldestInFlight == null) 0L else Duration.between(oldestInFlight, now).seconds.coerceAtLeast(0L)

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
