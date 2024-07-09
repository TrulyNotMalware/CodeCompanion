package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse

@Deprecated(message = "")
interface SlackRequestHandler {
    @Deprecated(message = "")
    fun sendToSlackServer(headers: SlackRequestHeaders, body: Any): SlackApiResponse
}