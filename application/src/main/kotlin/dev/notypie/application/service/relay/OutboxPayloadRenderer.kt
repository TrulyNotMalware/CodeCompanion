package dev.notypie.application.service.relay

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.DirectReply
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.OutboundRenderer
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.impl.command.isChannelRefused
import dev.notypie.repository.outbox.OutboundEnvelope
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion

enum class FallbackTrigger {
    ANY_DEFINITIVE_FAILURE,
    CHANNEL_REFUSED,
}

data class FallbackPlan(
    val envelope: OutboundEnvelope,
    val trigger: FallbackTrigger,
) {
    fun firesOn(result: CommandOutput): Boolean =
        when (trigger) {
            FallbackTrigger.ANY_DEFINITIVE_FAILURE -> true
            FallbackTrigger.CHANNEL_REFUSED -> result.isChannelRefused()
        }
}

data class RenderedRow(
    val payload: SlackEventPayload,
    val next: OutboundEnvelope?,
    val fallback: FallbackPlan? = null,
)

class OutboxPayloadRenderer(
    private val renderers: Map<Transport, OutboundRenderer>,
) {
    fun render(row: OutboxMessage): RenderedRow {
        require(row.schemaVersion in OutboxSchemaVersion.SUPPORTED) {
            "Unsupported outbox schemaVersion=${row.schemaVersion} eventId=${row.eventId}. " +
                "Supported versions: ${OutboxSchemaVersion.SUPPORTED}. " +
                "Refusing to dispatch a payload this binary cannot reason about."
        }
        val transport = Transport.valueOf(row.transport)
        val renderer =
            renderers[transport]
                ?: error("No renderer registered for transport=$transport eventId=${row.eventId}")
        val envelope = OutboundMessageCodec.decode(json = row.payload)
        return RenderedRow(
            payload = renderer.render(message = envelope.message, basicInfo = envelope.basicInfo),
            next = envelope.next(),
            fallback = envelope.fallbackPlan(),
        )
    }
}

private fun OutboundEnvelope.fallbackPlan(): FallbackPlan? =
    when (val head = message) {
        is OutboundMessage.ReplaceMessage ->
            head.fallback?.let { fallback ->
                FallbackPlan(
                    envelope = OutboundEnvelope(message = fallback, basicInfo = basicInfo),
                    trigger = FallbackTrigger.ANY_DEFINITIVE_FAILURE,
                )
            }

        is OutboundMessage.Ephemeral -> head.fallback?.let { reply -> directReplyPlan(reply = reply) }

        is OutboundMessage.ChannelMessage -> head.fallback?.let { reply -> directReplyPlan(reply = reply) }

        is OutboundMessage.DirectMessage,
        is OutboundMessage.UpdateMessage,
        is OutboundMessage.OpenModal,
        is OutboundMessage.Approval,
        is OutboundMessage.Notice,
        -> null
    }

private fun OutboundEnvelope.directReplyPlan(reply: DirectReply): FallbackPlan =
    FallbackPlan(
        envelope =
            OutboundEnvelope(
                message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = reply.recipient.id),
                        content = reply.content,
                    ),
                basicInfo = basicInfo.copy(channel = reply.recipient.id),
            ),
        trigger = FallbackTrigger.CHANNEL_REFUSED,
    )
