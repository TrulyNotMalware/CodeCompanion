package dev.notypie.application.service.cve.notification

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.UndeliveredCveEvent
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.outbox.CHAIN_TEXT_BUDGET
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.toChainHead
import dev.notypie.templates.SlackBlockLimits
import dev.notypie.templates.escapeMrkdwn
import dev.notypie.templates.truncateSectionText
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val log = KotlinLogging.logger {}

// claim() must keep REQUIRED propagation, or a save failure can't roll it back — leaking a false-delivered row.
class CveNotificationDispatcher(
    private val cveDeliveryRepository: CveDeliveryRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
    private val batchSize: Int,
    private val digestSendAt: LocalTime,
    private val digestZone: ZoneId,
    private val digestSummaryMaxLength: Int,
    private val deliveryHorizonDays: Long,
    private val clock: Clock,
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelay = 60_000)
    fun immediateTick() {
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
        val pairs =
            cveDeliveryRepository.findUndelivered(
                deliveryMode = CveDeliveryMode.IMMEDIATE,
                since = cveDeliveryRepository.dbNow().minusDays(deliveryHorizonDays),
                doneBefore = now,
                limit = batchSize,
            )
        if (pairs.isEmpty()) return

        var dispatched = 0
        pairs.forEach { pair ->
            transactionTemplate
                .runInTx { dispatchImmediate(pair = pair) }
                .onFailure { ex ->
                    log.error(ex) { "CVE immediate dispatch failed for event=${pair.eventId} user=${pair.userId}" }
                }.onSuccess { sent -> if (sent) dispatched++ }
        }
        if (dispatched > 0) {
            log.info { "CVE immediate dispatch pairs=${pairs.size} dispatched=$dispatched" }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun digestTick() {
        val zonedNow = clock.instant().atZone(digestZone)
        if (zonedNow.toLocalTime().isBefore(digestSendAt)) return

        val doneBefore =
            zonedNow
                .toLocalDate()
                .atTime(digestSendAt)
                .atZone(digestZone)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime()
        val since = cveDeliveryRepository.dbNow().minusDays(deliveryHorizonDays)
        val pairs =
            cveDeliveryRepository.findUndeliveredByUser(
                deliveryMode = CveDeliveryMode.DIGEST,
                since = since,
                doneBefore = doneBefore,
                limit = batchSize,
            )
        if (pairs.isEmpty()) return

        val byUser = pairs.groupBy { it.userId }
        val bundles =
            when {
                pairs.size < batchSize -> byUser
                byUser.size > 1 -> byUser - byUser.keys.last()
                else -> {
                    val userId = byUser.keys.single()
                    mapOf(userId to singleUserDay(userId = userId, since = since, doneBefore = doneBefore))
                }
            }

        var dispatchedUsers = 0
        var dispatchedEvents = 0
        bundles.forEach { (userId, userPairs) ->
            transactionTemplate
                .runInTx { dispatchDigest(userId = userId, userPairs = userPairs) }
                .onFailure { ex -> log.error(ex) { "CVE digest dispatch failed for user=$userId" } }
                .onSuccess { count ->
                    if (count > 0) {
                        dispatchedUsers++
                        dispatchedEvents += count
                    }
                }
        }
        if (dispatchedUsers > 0) {
            log.info { "CVE digest dispatch users=$dispatchedUsers events=$dispatchedEvents" }
        }
    }

    private fun singleUserDay(
        userId: String,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
    ): List<UndeliveredCveEvent> {
        val limit = batchSize * SINGLE_USER_DIGEST_PAGES
        val day =
            cveDeliveryRepository.findUndeliveredForUser(
                deliveryMode = CveDeliveryMode.DIGEST,
                userId = userId,
                since = since,
                doneBefore = doneBefore,
                limit = limit,
            )
        if (day.size >= limit) {
            log.warn { "CVE digest for user=$userId reached $limit events; the rest goes out in a later digest" }
        }
        return day
    }

    private fun dispatchImmediate(pair: UndeliveredCveEvent): Boolean {
        if (!cveDeliveryRepository.claim(eventId = pair.eventId, userId = pair.userId)) return false
        enqueue(userId = pair.userId, headline = IMMEDIATE_HEADLINE, markdown = immediateMarkdown(pair = pair))
        return true
    }

    private fun dispatchDigest(userId: String, userPairs: List<UndeliveredCveEvent>): Int {
        val bounded = userPairs.withinDigestBudget()
        if (bounded.size < userPairs.size) {
            log.info {
                "CVE digest for user=$userId holds ${bounded.size} of ${userPairs.size} events; " +
                    "the rest goes out in a later digest"
            }
        }
        val claimed = bounded.filter { cveDeliveryRepository.claim(eventId = it.eventId, userId = it.userId) }
        if (claimed.isEmpty()) return 0
        val parts = digestParts(events = claimed)
        outboxRepository.save(
            outboundMessagePort.toChainHead(
                messages =
                    parts.mapIndexed { index, markdown ->
                        val headline =
                            if (parts.size == 1) DIGEST_HEADLINE else "$DIGEST_HEADLINE (${index + 1}/${parts.size})"
                        directMessage(userId = userId, headline = headline, markdown = markdown)
                    },
                basicInfo = CommandBasicInfo.forOutbound(publisherId = userId, channel = userId),
            ),
        )
        return claimed.size
    }

    private fun enqueue(userId: String, headline: String, markdown: String) {
        outboxRepository.save(
            outboundMessagePort.toRow(
                message = directMessage(userId = userId, headline = headline, markdown = markdown),
                basicInfo = CommandBasicInfo.forOutbound(publisherId = userId, channel = userId),
            ),
        )
    }

    private fun directMessage(userId: String, headline: String, markdown: String): OutboundMessage =
        OutboundMessage.ChannelMessage(
            target = ConversationTarget(id = userId),
            content = MessageContent.Text(headline = headline, markdown = markdown),
        )

    private fun immediateMarkdown(pair: UndeliveredCveEvent): String {
        val head = "*${pair.topicDisplayName.escapeMrkdwn()}* — ${pair.title.escapeMrkdwn()}"
        val summary = pair.aiSummary
        return capBody(body = if (summary.isNullOrBlank()) head else "$head\n\n${summary.escapeMrkdwn()}")
    }

    private fun digestParts(events: List<UndeliveredCveEvent>): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var currentTopic: String? = null
        events.groupBy { it.topicDisplayName }.forEach { (topicDisplayName, topicEvents) ->
            val header = "*${topicDisplayName.escapeMrkdwn()}*"
            topicEvents.forEach { event ->
                val line = digestEventLine(event = event)
                val separator = if (currentTopic == topicDisplayName) "\n" else "\n\n$header\n"
                if (current.isNotEmpty() &&
                    current.length + separator.length + line.length <= SlackBlockLimits.MESSAGE_BODY_BUDGET
                ) {
                    current.append(separator).append(line)
                } else {
                    if (current.isNotEmpty()) parts += current.toString()
                    current.clear().append(
                        "$header\n$line".truncateSectionText(limit = SlackBlockLimits.MESSAGE_BODY_BUDGET),
                    )
                }
                currentTopic = topicDisplayName
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun List<UndeliveredCveEvent>.withinDigestBudget(): List<UndeliveredCveEvent> {
        val totals =
            runningFold(initial = 0) { total, event ->
                total + digestEventLine(event = event).length + event.topicDisplayName.escapeMrkdwn().length +
                    DIGEST_LINE_SEPARATORS
            }.drop(n = 1)
        return take(n = totals.count { it <= CHAIN_TEXT_BUDGET }.coerceAtLeast(minimumValue = 1))
    }

    // Oversized body would be rejected by Slack post-claim and retry forever — capping prevents that.
    private fun capBody(body: String): String = body.truncateSectionText(limit = CAPPED_BODY_MAX_LENGTH)

    private fun digestEventLine(event: UndeliveredCveEvent): String {
        val summary = event.aiSummary
        val title = "• *${event.title.escapeMrkdwn()}*"
        return if (summary.isNullOrBlank()) title else "$title\n${summary.take(digestSummaryMaxLength).escapeMrkdwn()}"
    }

    companion object {
        private const val IMMEDIATE_HEADLINE = "CodeCompanion — CVE alert"
        private const val DIGEST_HEADLINE = "CodeCompanion — CVE digest"

        private const val BODY_MAX_LENGTH = 2_900
        private const val CAPPED_BODY_MAX_LENGTH = BODY_MAX_LENGTH + 1 + SlackBlockLimits.TRUNCATION_MARKER.length
        private const val SINGLE_USER_DIGEST_PAGES = 10
        private const val DIGEST_LINE_SEPARATORS = 5
    }
}
