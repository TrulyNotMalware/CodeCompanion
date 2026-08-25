package dev.notypie.application.service.mention

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.inbound.InboundCommand
import org.springframework.util.MultiValueMap

interface AppMentionEventHandler {
    fun parseAppMentionEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): InboundCommand

    fun handleEvent(commandData: InboundCommand): CommandOutput

    fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): CommandOutput
}
