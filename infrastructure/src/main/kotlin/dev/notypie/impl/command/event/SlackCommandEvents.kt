package dev.notypie.impl.command.event

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import java.util.UUID

data class SendSlackMessageEvent(
    override val idempotencyKey: UUID,
    override val name: String = SendSlackMessageEvent::class.java.simpleName,
    override val payload: SlackEventPayload,
    override val isInternal: Boolean = true,
    override val destination: String,
    override val timestamp: Long,
    override val type: CommandDetailType,
) : CommandEvent<SlackEventPayload>

/**
 * Synchronous `views.open` command event. Must be consumed on the request thread because
 * [OpenViewPayloadContents.triggerId] expires in 3 seconds; `isInternal = true` keeps it on
 * the in-process event bus (never the outbox), handled by a dedicated non-`@Async` listener.
 */
data class OpenViewEvent(
    override val idempotencyKey: UUID,
    override val name: String = OpenViewEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: OpenViewPayloadContents,
    override val type: CommandDetailType,
) : CommandEvent<OpenViewPayloadContents>
