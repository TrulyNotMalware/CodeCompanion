package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackEventContents

interface SlackRequestBuilder {
    //Simple String response
    fun buildSimpleTextRequestBody(headLineText: String, channel: String, simpleString: String): SlackEventContents
}