package dev.notypie.application.service.cve.ops

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.entity.event.CveOpsAction
import dev.notypie.domain.command.entity.event.CveOpsPayload
import dev.notypie.domain.command.entity.event.CveOpsRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.CveTopicRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Executes the admin-only CVE operations mentions (`cve topics`, `cve topic activate|deactivate`,
 * `cve retry ...`). Mirrors [dev.notypie.application.service.command.RoleManagementService]: the parser
 * already gated the actor as ADMIN and validated the mention shape, so this listener applies the change
 * and confirms it on the originating channel. Unlike the subscription listener, a disabled feature does
 * not fail silently — an admin who typed a command gets a reply saying the feature is off.
 */
@Service
class CveOpsService(
    private val appConfig: AppConfig,
    private val cveTopicRepository: CveTopicRepository,
    private val cveEventRepository: CveEventRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    companion object {
        private const val RESPONSE_HEADLINE = "CodeCompanion — CVE operations"
        private const val FEATURE_DISABLED_MESSAGE = "The CVE feature is currently disabled."
    }

    private val maxRetries: Int = appConfig.ai.maxRetries

    // The staged reply is persisted by a BEFORE_COMMIT listener, so the repository write and the
    // confirmation must share one transaction (mirrors RoleManagementService).
    @Transactional
    @EventListener
    fun handleCveOps(event: CveOpsRequestEvent) {
        val payload = event.payload
        val text =
            if (!appConfig.cve.enabled) {
                FEATURE_DISABLED_MESSAGE
            } else {
                when (payload.action) {
                    CveOpsAction.LIST_TOPICS -> renderTopics()
                    CveOpsAction.ACTIVATE_TOPIC -> setActive(payload = payload, active = true)
                    CveOpsAction.DEACTIVATE_TOPIC -> setActive(payload = payload, active = false)
                    CveOpsAction.RETRY_ALL -> retryAll()
                    CveOpsAction.RETRY_EVENT -> retryOne(payload = payload)
                }
            }

        val staged =
            checkNotNull(
                outboundStager.stage(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = payload.responseBasicInfo.channel),
                            content = MessageContent.Text(headline = RESPONSE_HEADLINE, markdown = text),
                        ),
                    basicInfo = payload.responseBasicInfo,
                ),
            ) { "CVE ops reply failed to stage an outbox event: action=${payload.action}" }
        eventPublisher.publishOne(event = staged)
    }

    private fun renderTopics(): String {
        val topics = cveTopicRepository.findAllTopics()
        if (topics.isEmpty()) return "No CVE topics are configured."
        val counts =
            cveEventRepository
                .countEventsByTopic(topicIds = topics.map { it.id })
                .associate { it.topicId to it.count }
        val lines =
            topics.joinToString(separator = "\n") { topic ->
                "• *${topic.displayName}* (`${topic.topicKey}`) — ${topic.deliveryMode.name.lowercase()}, " +
                    "${stateOf(topic = topic)}, ${counts[topic.id] ?: 0L} event(s)"
            }
        return "CVE topics (${topics.size}):\n$lines"
    }

    private fun setActive(payload: CveOpsPayload, active: Boolean): String {
        val topicKey = checkNotNull(payload.topicKey) { "ACTIVATE_TOPIC/DEACTIVATE_TOPIC requires a topic key" }
        val topic =
            cveTopicRepository.findAllTopics().firstOrNull { it.topicKey == topicKey }
                ?: return "No CVE topic with key `$topicKey`."
        cveTopicRepository.setActive(topicKey = topicKey, active = active)
        val oldState = stateOf(topic = topic)
        val newState = if (active) "active" else "inactive"
        return "Topic *${topic.displayName}* (`$topicKey`): $oldState → $newState."
    }

    private fun retryAll(): String {
        val revived = cveEventRepository.resetDeadLetters(maxRetries = maxRetries)
        return "Re-queued $revived dead-letter event(s) for summarization."
    }

    private fun retryOne(payload: CveOpsPayload): String {
        val eventId = checkNotNull(payload.targetEventId) { "RETRY_EVENT requires an event id" }
        return if (cveEventRepository.resetDeadLetter(id = eventId, maxRetries = maxRetries) > 0) {
            "Re-queued event #$eventId for summarization."
        } else {
            "Event #$eventId is not a dead-letter (unknown id, or not FAILED past the retry limit)."
        }
    }

    private fun stateOf(topic: CveTopic): String = if (topic.active) "active" else "inactive"
}
