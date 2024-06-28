package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse

interface SlackRequestHandler {
    fun sendToSlackServer(headers: SlackRequestHeaders, body: Any): SlackApiResponse
}