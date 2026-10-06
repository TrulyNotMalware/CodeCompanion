package dev.notypie.repository.outbox

import com.fasterxml.jackson.annotation.JsonInclude
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion

const val CHAIN_TEXT_BUDGET: Int = 40_000

data class OutboundEnvelope(
    val message: OutboundMessage,
    val basicInfo: CommandBasicInfo,
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val continuation: List<OutboundMessage> = emptyList(),
) {
    fun next(): OutboundEnvelope? =
        continuation.firstOrNull()?.let { head ->
            OutboundEnvelope(message = head, basicInfo = basicInfo, continuation = continuation.drop(n = 1))
        }
}

fun OutboxMessage.chainedParts(): Int =
    if (schemaVersion == OutboxSchemaVersion.V2) {
        0
    } else {
        try {
            OutboundMessageCodec.decode(json = payload).continuation.size
        } catch (ex: OutboundMessageCodecException) {
            0
        }
    }
