package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload

interface OutboundMessageStager {
    /** Renders a transport-neutral OutboundMessage into a staged CommandEvent (or null when
     *  the message cannot/should not produce one). basicInfo supplies app/channel/idempotency. */
    fun stage(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>?
}
