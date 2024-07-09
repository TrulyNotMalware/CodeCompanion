package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackEventContents
import dev.notypie.domain.command.dto.response.SlackApiResponse

interface SlackApiRequester {
    //Simple String response
    @Deprecated(message = "")
    fun buildSimpleTextRequestBody(headLineText: String, channel: String, simpleString: String): SlackEventContents

    fun simpleTextRequest(headLineText: String, channel: String, simpleString: String): SlackApiResponse
}