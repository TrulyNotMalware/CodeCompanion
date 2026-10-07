package dev.notypie.repository.outbox

import dev.notypie.domain.command.dto.CommandBasicInfo
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
            schemaVersion = if (continuation.isEmpty()) OutboxSchemaVersion.V2 else OutboxSchemaVersion.V3,
        )
    }
}
