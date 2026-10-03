package dev.notypie.repository.outbox.dto

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
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
    val commandDetailType: CommandDetailType,
    val messageTs: String = "",
) : OutboxUpdateEvent(eventId = eventId, status = MessageStatus.SUCCESS)

fun CommandOutput.toOutboxUpdateEvent(eventId: UUID): OutboxUpdateEvent =
    if (ok) {
        MessagePublishSuccessEvent(eventId = eventId, commandDetailType = commandDetailType, messageTs = messageTs)
    } else {
        MessagePublishFailedEvent(eventId = eventId, reason = errorReason)
    }
