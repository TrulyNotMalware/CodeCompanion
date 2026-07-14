package dev.notypie.application.service.cve.query

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.CveLatestPayload
import dev.notypie.domain.command.entity.event.CveLatestRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveRecentEvent
import dev.notypie.repository.cve.CveSubscriptionRepository
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * Answers `/latest [topic-key]` with a DM of the most recent DONE-summarized events — a pure DB read,
 * zero AI calls (the summary was produced once at collection time). With no argument it reads across the
 * caller's subscriptions; with a topic key it scopes to that single active topic. Mirrors the
 * subscription listener's DM path (`chat.postMessage(channel=userId)` via `forOutbound`).
 */
@Service
class CveLatestQueryService(
    private val appConfig: AppConfig,
    private val cveSubscriptionRepository: CveSubscriptionRepository,
    private val cveTopicRepository: CveTopicRepository,
    private val cveEventRepository: CveEventRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    companion object {
        private const val RESPONSE_HEADLINE = "CodeCompanion — latest CVE updates"
        private const val LATEST_LIMIT = 5
        private const val SUMMARY_MAX_LENGTH = 700
        private const val BODY_MAX_LENGTH = 2_900
    }

    @Transactional
    @EventListener
    fun handleCveLatest(event: CveLatestRequestEvent) {
        // The slash layer already gates, but a request can be in flight across a runtime toggle-off —
        // fail closed here too so a disabled feature never reads or DMs.
        if (!appConfig.cve.enabled) {
            log.warn { "CVE latest event ignored while the feature is disabled: userId=${event.payload.userId}" }
            return
        }
        val payload = event.payload
        val text = renderLatest(payload = payload)

        val dmBasicInfo =
            CommandBasicInfo.forOutbound(
                publisherId = payload.userId,
                channel = payload.userId,
                appId = payload.responseBasicInfo.appId,
                idempotencyKey = event.idempotencyKey,
            )
        val staged =
            checkNotNull(
                outboundStager.stage(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = payload.userId),
                            content = MessageContent.Text(headline = RESPONSE_HEADLINE, markdown = text),
                        ),
                    basicInfo = dmBasicInfo,
                ),
            ) { "CVE latest reply failed to stage an outbox event: userId=${payload.userId}" }
        eventPublisher.publishOne(event = staged)
    }

    private fun renderLatest(payload: CveLatestPayload): String {
        val topicKey = payload.topicKey
        val topicIds: List<Long>
        val emptyMessage: String
        if (topicKey == null) {
            val subscribed = cveSubscriptionRepository.findSubscribedTopics(userId = payload.userId)
            if (subscribed.isEmpty()) {
                return "You have no CVE topic subscriptions. Use `/subscribe` to pick topics first."
            }
            topicIds = subscribed.map { it.id }
            emptyMessage = "No recent CVE updates for your subscribed topics yet."
        } else {
            val topic =
                cveTopicRepository.findActiveTopics().firstOrNull { it.topicKey == topicKey }
                    ?: return "Topic `$topicKey` is not available."
            topicIds = listOf(topic.id)
            emptyMessage = "No recent CVE updates for *${topic.displayName}* yet."
        }

        val recent = cveEventRepository.findRecentDoneEvents(topicIds = topicIds, limit = LATEST_LIMIT)
        if (recent.isEmpty()) return emptyMessage
        val body = recent.joinToString(separator = "\n\n") { render(event = it) }
        // The whole body lands in ONE Slack section block, and Slack rejects mrkdwn over 3000 chars
        // (invalid_blocks) — so the aggregate is capped, not just each summary, or the DM never posts.
        return if (body.length > BODY_MAX_LENGTH) "${body.take(BODY_MAX_LENGTH)}\n…(truncated)" else body
    }

    private fun render(event: CveRecentEvent): String =
        "*${event.topicDisplayName}* — *${event.title}*\n${event.aiSummary.orEmpty().take(SUMMARY_MAX_LENGTH)}"
}
