package dev.notypie.repository.outbox.dto

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.event.SlackEventPayload
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.util.UUID

sealed class OutboxUpdateEvent(
    open val eventId: UUID,
    open val status: MessageStatus,
)

data class MessagePublishFailedEvent(
    override val eventId: UUID,
    val reason: String,
) : OutboxUpdateEvent(eventId = eventId, status = MessageStatus.FAILURE)

data class MessagePublishSuccessEvent(
    override val eventId: UUID,
) : OutboxUpdateEvent(eventId = eventId, status = MessageStatus.SUCCESS)

data class NewMessagePublishedEvent(
    val reason: String,
    val outboxMessage: OutboxMessage,
    val slackEventPayload: SlackEventPayload,
)

fun CommandOutput.toOutboxUpdateEvent(eventId: UUID): OutboxUpdateEvent =
    if (ok) {
        MessagePublishSuccessEvent(eventId = eventId)
    } else {
        MessagePublishFailedEvent(eventId = eventId, reason = errorReason)
    }
