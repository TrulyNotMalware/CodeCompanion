package dev.notypie.repository.outbox.dto

import dev.notypie.domain.common.event.SlackEvent
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.util.UUID

sealed class OutboxUpdateEvent(
    open val idempotencyKey: UUID,
    open val status: MessageStatus
)

data class MessagePublishFailedEvent(
    override val idempotencyKey: UUID,
    val reason: String
): OutboxUpdateEvent(idempotencyKey = idempotencyKey, status = MessageStatus.FAILURE)

data class MessagePublishSuccessEvent(
    override val idempotencyKey: UUID
): OutboxUpdateEvent(idempotencyKey = idempotencyKey, status = MessageStatus.SUCCESS)

data class NewMessagePublishedEvent(
    val reason: String,
    val outboxMessage: OutboxMessage,
    val slackEvent: SlackEvent
)

fun CommandOutput.toOutboxUpdateEvent(): OutboxUpdateEvent =
    if( this.ok ) MessagePublishSuccessEvent(idempotencyKey = this.idempotencyKey)
    else MessagePublishFailedEvent(idempotencyKey = this.idempotencyKey, reason = this.errorReason)