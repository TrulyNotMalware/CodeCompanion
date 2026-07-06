package dev.notypie.repository.outbox.dto

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.repository.outbox.schema.MessageStatus
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
    val messageTs: String = "",
) : OutboxUpdateEvent(eventId = eventId, status = MessageStatus.SUCCESS)

fun CommandOutput.toOutboxUpdateEvent(eventId: UUID): OutboxUpdateEvent =
    if (ok) {
        MessagePublishSuccessEvent(eventId = eventId, messageTs = messageTs)
    } else {
        MessagePublishFailedEvent(eventId = eventId, reason = errorReason)
    }
