package dev.notypie.impl.command.event

import dev.notypie.domain.command.dto.response.CommandOutput

interface MessageDispatcher {
    fun dispatch(event: SlackEventPayload): CommandOutput

    fun dispatchImmediate(event: OpenViewPayloadContents): CommandOutput
}
