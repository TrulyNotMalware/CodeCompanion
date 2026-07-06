package dev.notypie.impl.command.event

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

/**
 * Transport-neutral outbound effect awaiting persistence to the outbox. Carries the raw
 * [OutboundMessage] and its command context rather than a rendered payload: the row is decoded and
 * rendered to a wire payload only later, at deliver time, by the relay. Rides the in-process event
 * bus ([isInternal] = true) so a BEFORE_COMMIT listener can turn it into a row via
 * [dev.notypie.repository.outbox.OutboundMessagePort].
 */
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
    // Inert for an internal event (never Kafka-routed); the effective routing type lives inside the
    // encoded message. Defaulted so callers need not synthesize one.
    override val type: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
) : CommandEvent<OutboundMessageEnqueuedPayload>
