package dev.notypie.application.service.cve.notification

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.UndeliveredCveEvent
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/**
 * DMs DONE-summarized events to their subscribers exactly once, through the existing outbox. The
 * per-topic delivery mode decides timing: IMMEDIATE events go out on a frequent tick, DIGEST events
 * are bundled into one daily DM per user after a configured local time. Both ticks pull candidate
 * (event, user) pairs from [CveDeliveryRepository.findUndelivered] and, per unit of work, run the
 * atomic [claim][CveDeliveryRepository.claim] and the outbox save in ONE transaction ([runInTx]) —
 * the claim's `@Transactional` is REQUIRED so it joins that transaction. So a save failure rolls the
 * claim back and the pair (immediate) or the whole user bundle (digest) re-drives next tick; the
 * ledger never records a delivery that did not enqueue. A single unit's failure is isolated (logged)
 * and never aborts the rest. Feature-gated as a bean in CveConfiguration.
 */
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
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelay = 60_000)
    fun immediateTick() {
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
        val pairs =
            cveDeliveryRepository.findUndelivered(
                deliveryMode = CveDeliveryMode.IMMEDIATE,
                // The horizon bounds DB-stamped created_at, so it is computed on the DB clock;
                // doneBefore bounds app-stamped updated_at, so it stays on the app clock.
                since = cveDeliveryRepository.dbNow().minusDays(deliveryHorizonDays),
                // Immediate: everything summarized by now is due.
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
        // Hold digests until the configured local send time; before it, touch nothing.
        if (zonedNow.toLocalTime().isBefore(digestSendAt)) return

        // Cutoff = today's send time: only events summarized before it are visible today, so the first
        // post-send-time tick drains the day and later ticks find nothing (one DM per user per day);
        // events summarized after it roll into tomorrow's digest. No separate once-per-day ledger.
        val doneBefore =
            zonedNow
                .toLocalDate()
                .atTime(digestSendAt)
                .atZone(digestZone)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime()
        val pairs =
            cveDeliveryRepository.findUndelivered(
                deliveryMode = CveDeliveryMode.DIGEST,
                // DB clock for the created_at horizon, app clock for the updated_at cutoff above.
                since = cveDeliveryRepository.dbNow().minusDays(deliveryHorizonDays),
                doneBefore = doneBefore,
                limit = batchSize,
            )
        if (pairs.isEmpty()) return

        var dispatchedUsers = 0
        var dispatchedEvents = 0
        pairs.groupBy { it.userId }.forEach { (userId, userPairs) ->
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

    // Runs inside one transaction: a lost claim skips the pair; otherwise the DM is saved in the same
    // tx, so a save failure rolls the claim back for a clean retry. Returns true when a DM was sent.
    private fun dispatchImmediate(pair: UndeliveredCveEvent): Boolean {
        if (!cveDeliveryRepository.claim(eventId = pair.eventId, userId = pair.userId)) return false
        enqueue(userId = pair.userId, headline = IMMEDIATE_HEADLINE, markdown = immediateMarkdown(pair = pair))
        return true
    }

    // Runs inside one transaction: claims each of the user's events, then saves ONE digest DM for the
    // won ones. A save failure rolls back every claim, so the whole bundle re-drives next tick.
    // Returns the number of events bundled (0 = every claim lost, nothing sent).
    private fun dispatchDigest(userId: String, userPairs: List<UndeliveredCveEvent>): Int {
        val claimed = userPairs.filter { cveDeliveryRepository.claim(eventId = it.eventId, userId = it.userId) }
        if (claimed.isEmpty()) return 0
        enqueue(userId = userId, headline = DIGEST_HEADLINE, markdown = digestMarkdown(events = claimed))
        return claimed.size
    }

    private fun enqueue(userId: String, headline: String, markdown: String) {
        val commandBasicInfo = CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
        val message =
            OutboundMessage.ChannelMessage(
                target = ConversationTarget(id = userId),
                content = MessageContent.Text(headline = headline, markdown = markdown),
            )
        outboxRepository.save(outboundMessagePort.toRow(message = message, basicInfo = commandBasicInfo))
    }

    private fun immediateMarkdown(pair: UndeliveredCveEvent): String {
        val head = "*${pair.topicDisplayName}* — ${pair.title}"
        val summary = pair.aiSummary
        return capBody(body = if (summary.isNullOrBlank()) head else "$head\n\n$summary")
    }

    private fun digestMarkdown(events: List<UndeliveredCveEvent>): String =
        events
            .groupBy { it.topicDisplayName }
            .entries
            .joinToString(separator = "\n\n") { (topicDisplayName, topicEvents) ->
                val lines = topicEvents.joinToString(separator = "\n") { digestEventLine(event = it) }
                "*$topicDisplayName*\n$lines"
            }.let { capBody(body = it) }

    // An oversized body would be rejected by Slack AFTER the delivery claim committed — the outbox
    // would retry the same rejected payload forever and the notification silently drops. Capping
    // the aggregate under the 3000-char section limit removes that failure mode entirely.
    private fun capBody(body: String): String =
        if (body.length > BODY_MAX_LENGTH) "${body.take(BODY_MAX_LENGTH)}\n…(truncated)" else body

    private fun digestEventLine(event: UndeliveredCveEvent): String {
        val summary = event.aiSummary
        return if (summary.isNullOrBlank()) {
            "• *${event.title}*"
        } else {
            "• *${event.title}*\n${summary.take(digestSummaryMaxLength)}"
        }
    }

    companion object {
        private const val IMMEDIATE_HEADLINE = "CodeCompanion — CVE alert"
        private const val DIGEST_HEADLINE = "CodeCompanion — CVE digest"

        // Slack section-block text tops out at 3000 chars; headroom covers the truncation marker.
        private const val BODY_MAX_LENGTH = 2_900
    }
}
