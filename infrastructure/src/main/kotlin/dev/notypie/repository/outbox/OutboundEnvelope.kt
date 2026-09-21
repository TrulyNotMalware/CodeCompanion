package dev.notypie.repository.outbox

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.OutboundMessage

data class OutboundEnvelope(
    val message: OutboundMessage,
    val basicInfo: CommandBasicInfo,
)
