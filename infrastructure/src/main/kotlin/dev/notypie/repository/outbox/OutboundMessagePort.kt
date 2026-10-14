package dev.notypie.repository.outbox

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import java.time.LocalDateTime
import java.util.UUID

interface OutboundMessagePort {
    fun toRow(
        message: OutboundMessage,
        basicInfo: CommandBasicInfo,
        transport: Transport = Transport.SLACK,
        continuation: List<OutboundMessage> = emptyList(),
    ): OutboxMessage
}

fun OutboundMessagePort.toChainHead(messages: List<OutboundMessage>, basicInfo: CommandBasicInfo): OutboxMessage =
    toRow(message = messages.first(), basicInfo = basicInfo, continuation = messages.drop(n = 1))

class CodecOutboundMessagePort : OutboundMessagePort {
    override fun toRow(
        message: OutboundMessage,
        basicInfo: CommandBasicInfo,
        transport: Transport,
        continuation: List<OutboundMessage>,
    ): OutboxMessage {
        val envelope = OutboundEnvelope(message = message, basicInfo = basicInfo, continuation = continuation)
        return OutboxMessage(
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = basicInfo.idempotencyKey.toString(),
            publisherId = basicInfo.publisherId,
            transport = transport.name,
            payload = OutboundMessageCodec.encode(envelope = envelope),
            createdAt = LocalDateTime.now(),
            schemaVersion = schemaVersionOf(message = message, continuation = continuation),
        )
    }
}

private fun schemaVersionOf(message: OutboundMessage, continuation: List<OutboundMessage>): Int {
    val chainVersion = if (continuation.isEmpty()) OutboxSchemaVersion.V2 else OutboxSchemaVersion.V3
    return maxOf(chainVersion, (listOf(message) + continuation).maxOf { part -> part.requiredSchemaVersion() })
}

private fun OutboundMessage.requiredSchemaVersion(): Int =
    when (this) {
        is OutboundMessage.ChannelMessage ->
            when {
                fallback != null -> OutboxSchemaVersion.V6
                content is MessageContent.AgentQuestion -> OutboxSchemaVersion.V5
                else -> OutboxSchemaVersion.V2
            }

        is OutboundMessage.Ephemeral -> if (fallback != null) OutboxSchemaVersion.V6 else OutboxSchemaVersion.V2

        is OutboundMessage.ReplaceMessage ->
            fallback?.let { ephemeral -> maxOf(OutboxSchemaVersion.V4, ephemeral.requiredSchemaVersion()) }
                ?: OutboxSchemaVersion.V2

        is OutboundMessage.DirectMessage,
        is OutboundMessage.UpdateMessage,
        is OutboundMessage.OpenModal,
        is OutboundMessage.Approval,
        is OutboundMessage.Notice,
        -> OutboxSchemaVersion.V2
    }
