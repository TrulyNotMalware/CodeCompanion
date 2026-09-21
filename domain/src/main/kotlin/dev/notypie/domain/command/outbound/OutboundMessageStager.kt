package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload

interface OutboundMessageStager {
    fun stage(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>?
}
