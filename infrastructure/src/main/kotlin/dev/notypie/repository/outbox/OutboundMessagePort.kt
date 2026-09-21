package dev.notypie.repository.outbox

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.time.LocalDateTime
import java.util.UUID

interface OutboundMessagePort {
    fun toRow(
        message: OutboundMessage,
        basicInfo: CommandBasicInfo,
        transport: Transport = Transport.SLACK,
    ): OutboxMessage
}

class CodecOutboundMessagePort : OutboundMessagePort {
    override fun toRow(message: OutboundMessage, basicInfo: CommandBasicInfo, transport: Transport): OutboxMessage {
        val envelope = OutboundEnvelope(message = message, basicInfo = basicInfo)
        return OutboxMessage(
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = basicInfo.idempotencyKey.toString(),
            publisherId = basicInfo.publisherId,
            transport = transport.name,
            payload = OutboundMessageCodec.encode(envelope = envelope),
            createdAt = LocalDateTime.now(),
        )
    }
}
