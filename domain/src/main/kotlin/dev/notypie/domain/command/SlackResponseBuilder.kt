package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackEventContents

interface SlackResponseBuilder {
    //Simple String response
    fun buildRequestBody(channel: String, simpleString: String): SlackEventContents
}