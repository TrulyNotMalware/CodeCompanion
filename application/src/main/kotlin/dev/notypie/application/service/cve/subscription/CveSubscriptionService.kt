package dev.notypie.application.service.cve.subscription

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.CveSubscriptionAction
import dev.notypie.domain.command.entity.event.CveSubscriptionPayload
import dev.notypie.domain.command.entity.event.CveSubscriptionRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.cve.CveSubscriptionRepository
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * Applies a CVE subscription change and confirms it in a DM. Mirrors
 * [dev.notypie.application.service.command.RoleManagementService]: the resolver lifted the intent into
 * a [CveSubscriptionRequestEvent], and this listener owns the repository write plus the reply. The
 * confirmation is a `ChannelMessage` whose channel is the user id — `chat.postMessage(channel=userId)`
 * delivers it as a DM, so no `conversations.open` is needed.
 *
 * `@Transactional` so the write and the outbox-staged reply (persisted by a BEFORE_COMMIT listener)
 * share one boundary even though the event is published from the command executor.
 */
@Service
class CveSubscriptionService(
    private val appConfig: AppConfig,
    private val cveSubscriptionRepository: CveSubscriptionRepository,
    private val cveTopicRepository: CveTopicRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    companion object {
        private const val RESPONSE_HEADLINE = "CodeCompanion — CVE subscriptions"
    }

    @Transactional
    @EventListener
    fun handleCveSubscription(event: CveSubscriptionRequestEvent) {
        // The slash layer already gates, but a submission can still be in flight across a runtime
        // toggle-off — fail closed here too so a disabled feature never writes or DMs.
        if (!appConfig.cve.enabled) {
            log.warn { "CVE subscription event ignored while the feature is disabled: action=${event.payload.action}" }
            return
        }
        val payload = event.payload
        val text =
            when (payload.action) {
                CveSubscriptionAction.SUBSCRIBE -> subscribe(payload = payload)
                CveSubscriptionAction.UNSUBSCRIBE -> unsubscribe(payload = payload)
                CveSubscriptionAction.LIST -> renderSubscriptions(userId = payload.userId)
            }

        // DM: chat.postMessage(channel=userId). forOutbound keeps appToken blank so the reply
        // authenticates with the bot token, and the channel is the user id itself.
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
            ) { "CVE subscription reply failed to stage an outbox event: action=${payload.action}" }
        eventPublisher.publishOne(event = staged)
    }

    private fun subscribe(payload: CveSubscriptionPayload): String {
        val activeByKey = cveTopicRepository.findActiveTopics().associateBy { it.topicKey }
        val requested = payload.topicKeys.distinct()
        val known = requested.mapNotNull { activeByKey[it] }
        val unknown = requested.filter { it !in activeByKey }
        cveSubscriptionRepository.subscribe(userId = payload.userId, topicIds = known.map { it.id })
        val base =
            if (known.isEmpty()) {
                "No topics were subscribed."
            } else {
                "Subscribed to ${known.size} topic(s): ${topicNames(topics = known)}."
            }
        return base + skippedSuffix(unknownKeys = unknown, reason = "unavailable")
    }

    private fun unsubscribe(payload: CveSubscriptionPayload): String {
        val subscribedByKey =
            cveSubscriptionRepository.findSubscribedTopics(userId = payload.userId).associateBy { it.topicKey }
        val requested = payload.topicKeys.distinct()
        val known = requested.mapNotNull { subscribedByKey[it] }
        val unknown = requested.filter { it !in subscribedByKey }
        cveSubscriptionRepository.unsubscribe(userId = payload.userId, topicIds = known.map { it.id })
        val base =
            if (known.isEmpty()) {
                "No matching subscriptions were removed."
            } else {
                "Unsubscribed from ${known.size} topic(s): ${topicNames(topics = known)}."
            }
        return base + skippedSuffix(unknownKeys = unknown, reason = "not subscribed")
    }

    private fun renderSubscriptions(userId: String): String {
        val topics = cveSubscriptionRepository.findSubscribedTopics(userId = userId)
        if (topics.isEmpty()) return "You have no CVE topic subscriptions."
        val lines = topics.joinToString(separator = "\n") { "• *${it.displayName}* (`${it.topicKey}`)" }
        return "You're subscribed to ${topics.size} topic(s):\n$lines"
    }

    private fun topicNames(topics: List<CveTopic>): String =
        topics.joinToString(separator = ", ") { "*${it.displayName}*" }

    private fun skippedSuffix(unknownKeys: List<String>, reason: String): String =
        if (unknownKeys.isEmpty()) {
            ""
        } else {
            " Skipped $reason topics: ${unknownKeys.joinToString(separator = ", ") { "`$it`" }}."
        }
}
