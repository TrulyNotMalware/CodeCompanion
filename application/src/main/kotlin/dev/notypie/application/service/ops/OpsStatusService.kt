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
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

private val CVE_WINDOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
    // Always wired (JpaConfiguration registers the CVE repositories unconditionally), so injection is
    // safe even when the CVE feature is off; the section is only rendered when cve.enabled is true.
    private val cveTopicRepository: CveTopicRepository,
    private val cveEventRepository: CveEventRepository,
    private val cveCollectLedgerRepository: CveCollectLedgerRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) {
    private val stuckThresholdSeconds: Long = appConfig.outbox.health.stuckThresholdSeconds
    private val cveEnabled: Boolean = appConfig.cve.enabled
    private val cveMaxRetries: Int = appConfig.ai.maxRetries

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

    // Internal: the MCP `get_status` tool renders the same report so chat and tool output agree.
    internal fun renderReport(): String {
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
            if (cveEnabled) {
                appendLine()
                append(cveSection())
            }
        }
    }

    // CVE feed health: active topic count, event backlog by summary status (FAILED split into still-
    // retryable vs dead-letter at the retry ceiling), and the newest collect window. Rendered only when
    // the feature is on; the outbox section above stays identical either way.
    private fun cveSection(): String {
        val activeTopics = cveTopicRepository.countActive()
        val pending = cveEventRepository.countByStatus(status = CveSummaryStatus.PENDING)
        val summarizing = cveEventRepository.countByStatus(status = CveSummaryStatus.SUMMARIZING)
        val retryable = cveEventRepository.countFailedRetryable(maxRetries = cveMaxRetries)
        val deadLetter = cveEventRepository.countDeadLetter(maxRetries = cveMaxRetries)
        val lastCollected = cveCollectLedgerRepository.latestWindowStart()?.format(CVE_WINDOW_FORMAT) ?: "never"
        return buildString {
            appendLine("• *CVE topics:* $activeTopics active")
            appendLine(
                "• *CVE events:* $pending pending, $summarizing summarizing, " +
                    "$retryable failed (retryable), $deadLetter dead-letter",
            )
            append("• *CVE last collect window:* $lastCollected")
        }
    }
}
