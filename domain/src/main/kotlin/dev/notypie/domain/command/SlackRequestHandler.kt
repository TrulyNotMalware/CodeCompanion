package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackRequestHeaders

interface SlackRequestHandler {
    fun sendToSlackServer(headers: SlackRequestHeaders, body: Any)
}