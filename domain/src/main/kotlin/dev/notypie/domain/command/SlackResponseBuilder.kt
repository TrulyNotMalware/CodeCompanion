package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackEventContents

interface SlackResponseBuilder {
    fun buildRequestBody(): SlackEventContents
}