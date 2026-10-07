package dev.notypie.application.service.ops

import dev.notypie.application.common.detachedTemplate
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.health.readOutboxHealth
import dev.notypie.application.service.relay.AccessBlockedTracker
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

private val CVE_WINDOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Service
class OpsStatusService(
    private val outboxRepository: MessageOutboxRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val cveTopicRepository: CveTopicRepository,
    private val cveEventRepository: CveEventRepository,
    private val cveCollectLedgerRepository: CveCollectLedgerRepository,
    private val accessBlockedTracker: AccessBlockedTracker,
    private val clock: Clock,
    appConfig: AppConfig,
    transactionManager: PlatformTransactionManager,
) {
    private val reportTemplate: TransactionTemplate = detachedTemplate(transactionManager = transactionManager)
    private val healthConfig: AppConfig.Outbox.Health = appConfig.outbox.health
    private val cveEnabled: Boolean = appConfig.cve.enabled
    private val cveMaxRetries: Int = appConfig.ai.maxRetries

    @EventListener
    fun handleStatusReport(event: StatusReportRequestEvent) {
        val payload = event.payload
        val text =
            try {
                checkNotNull(reportTemplate.execute { renderReport() })
            } catch (exception: Exception) {
                log.error(exception) {
                    "Failed to render outbox status report idempotencyKey=${event.idempotencyKey}"
                }
                "Failed to read outbox status. Check application logs."
            }

        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = payload.responseBasicInfo.channel),
                        recipient = UserRef(id = payload.responseBasicInfo.publisherId),
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

    internal fun renderReport(): String {
        val health =
            outboxRepository.readOutboxHealth(
                clock = clock,
                health = healthConfig,
                accessBlockedTracker = accessBlockedTracker,
            )
        val healthLine = if (health.healthy) "*Health:* :large_green_circle: UP" else "*Health:* :red_circle: DOWN"

        return buildString {
            with(health) {
                appendLine(
                    "• *Pending:* $pendingCount (oldest ${oldestPendingAgeSeconds}s ago, stuck $stuckPendingCount)",
                )
                appendLine(
                    "• *In-flight:* $inFlightCount (oldest ${oldestInFlightAgeSeconds}s ago, stuck $stuckInFlightCount)",
                )
                appendLine("• *Retrying:* $retryingCount (sent at least ${retryingSendThreshold}x, still in flight)")
                val accessLine = if (accessBlocked) "rows held, last at $lastAccessBlockedAt" else "none held"
                appendLine("• *Slack access blocked:* $accessLine (window ${accessBlockedWindowSeconds}s)")
                appendLine("• Stuck threshold: ${stuckThresholdSeconds}s")
            }
            append(healthLine)
            if (cveEnabled) {
                appendLine()
                append(cveSection())
            }
        }
    }

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
