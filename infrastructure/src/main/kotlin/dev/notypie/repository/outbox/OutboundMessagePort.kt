package dev.notypie.repository.outbox

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.time.LocalDateTime
import java.util.UUID

/**
 * Builds a transport-neutral outbox row from a domain [OutboundMessage] plus its command context.
 * The message is codec-encoded into the row's single payload column and rendered to a wire payload
 * only later, at deliver time, by the relay. Every caller persists the returned row through its own
 * repository/transaction, so the port stays a pure value builder with no persistence of its own.
 */
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
