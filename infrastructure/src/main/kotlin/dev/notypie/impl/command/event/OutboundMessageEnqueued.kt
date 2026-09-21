package dev.notypie.impl.command.event

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

data class OutboundMessageEnqueuedPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val message: OutboundMessage,
    val basicInfo: CommandBasicInfo,
) : EventPayload

data class OutboundMessageEnqueued(
    override val idempotencyKey: UUID,
    override val name: String = OutboundMessageEnqueued::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: OutboundMessageEnqueuedPayload,
    override val type: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
) : CommandEvent<OutboundMessageEnqueuedPayload>
