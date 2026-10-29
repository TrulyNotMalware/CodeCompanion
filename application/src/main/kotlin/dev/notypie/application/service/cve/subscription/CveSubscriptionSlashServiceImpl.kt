package dev.notypie.application.service.cve.subscription

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.entity.slash.CveSubscribeSlashCommand
import dev.notypie.domain.command.entity.slash.CveSubscriptionsSlashCommand
import dev.notypie.domain.command.entity.slash.CveUnsubscribeSlashCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.TopicOption
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.outbound.directFallbackOf
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import dev.notypie.repository.cve.CveSubscriptionRepository
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class CveSubscriptionSlashServiceImpl(
    private val appConfig: AppConfig,
    private val cveTopicRepository: CveTopicRepository,
    private val cveSubscriptionRepository: CveSubscriptionRepository,
    private val commandExecutor: CommandExecutor,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) : CveSubscriptionSlashService {
    @Transactional
    override fun handleSubscribe(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        if (!appConfig.cve.enabled) {
            log.warn { "CVE feature disabled; ignoring /subscribe from ${commandData.actorId}" }
            return
        }
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val topics = cveTopicRepository.findActiveTopics()
        if (topics.isEmpty()) {
            publishInfo(
                commandData = commandData,
                idempotencyKey = idempotencyKey,
                message = "There are no CVE topics available to subscribe to yet.",
            )
            return
        }
        commandExecutor.execute(
            command =
                CveSubscribeSlashCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                    topics = topics.map { it.toOption() },
                ),
        )
    }

    @Transactional
    override fun handleUnsubscribe(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        if (!appConfig.cve.enabled) {
            log.warn { "CVE feature disabled; ignoring /unsubscribe from ${commandData.actorId}" }
            return
        }
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val subscribed = cveSubscriptionRepository.findSubscribedTopics(userId = commandData.actorId)
        if (subscribed.isEmpty()) {
            publishInfo(
                commandData = commandData,
                idempotencyKey = idempotencyKey,
                message = "You have no CVE topic subscriptions to remove.",
            )
            return
        }
        commandExecutor.execute(
            command =
                CveUnsubscribeSlashCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                    topics = subscribed.map { it.toOption() },
                ),
        )
    }

    @Transactional
    override fun handleSubscriptions(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        if (!appConfig.cve.enabled) {
            log.warn { "CVE feature disabled; ignoring /subscriptions from ${commandData.actorId}" }
            return
        }
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        commandExecutor.execute(
            command =
                CveSubscriptionsSlashCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                ),
        )
    }

    private fun publishInfo(commandData: InboundCommand, idempotencyKey: UUID, message: String) {
        val basicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        val channel = ConversationTarget(id = basicInfo.channel)
        val content = MessageContent.Text(headline = null, markdown = message)
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = channel,
                        recipient = null,
                        content = content,
                        fallback =
                            directFallbackOf(
                                channel = channel,
                                recipient = UserRef(id = basicInfo.publisherId),
                                content = content,
                            ),
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    private fun CveTopic.toOption(): TopicOption = TopicOption(key = topicKey, label = displayName)
}
