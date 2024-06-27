package dev.notypie.impl.command

import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackRequestHeaders

class SlackRequestHandlerImpl : SlackRequestHandler {
    override fun sendToSlackServer(headers: SlackRequestHeaders, body: Any) {
        TODO("Not yet implemented")
    }

}