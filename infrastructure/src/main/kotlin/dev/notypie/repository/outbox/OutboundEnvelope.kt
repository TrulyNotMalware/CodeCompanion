package dev.notypie.repository.outbox

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.OutboundMessage

/**
 * Transport-neutral unit stored in the outbox: the outbound effect plus the command context needed
 * to eventually route and authenticate it. Serialized to a single JSON column by [OutboundMessageCodec].
 */
data class OutboundEnvelope(
    val message: OutboundMessage,
    val basicInfo: CommandBasicInfo,
)
