package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackEventContents

interface SlackRequestBuilder {
    fun buildRequestBody(): SlackEventContents
}