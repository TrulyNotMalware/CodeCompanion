package dev.notypie.application.service.agent

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConversePayload
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger {}

@Service
class AgentAskThreadStarter(
    private val outboxRepository: MessageOutboxRepository,
    private val eventPublisher: EventPublisher,
) {
    @EventListener
    fun onDelivered(event: MessagePublishSuccessEvent) {
        if (event.commandDetailType != CommandDetailType.AGENT_ASK_QUESTION || event.messageTs.isBlank()) return
        val row = outboxRepository.findByIdOrNull(id = event.eventId.toString())
        if (row == null) {
            log.warn { "Asked question row is gone; no answer thread starts for eventId=${event.eventId}" }
            return
        }
        val envelope = OutboundMessageCodec.decode(json = row.payload)
        val question = (envelope.message as? OutboundMessage.ChannelMessage)?.content as? MessageContent.AgentQuestion
        if (question == null) {
            log.warn { "Row eventId=${event.eventId} is tagged as an asked question but carries none; skipping" }
            return
        }
        eventPublisher.publishOne(
            event =
                AgentConverseRequestEvent(
                    idempotencyKey = envelope.basicInfo.idempotencyKey,
                    payload =
                        AgentConversePayload(
                            prompt = question.prompt,
                            threadId = event.messageTs,
                            requesterName = question.requesterName,
                            channelName = question.channelName,
                            responseBasicInfo = envelope.basicInfo,
                        ),
                    type = CommandDetailType.AGENT_CONVERSE,
                ),
        )
    }
}
