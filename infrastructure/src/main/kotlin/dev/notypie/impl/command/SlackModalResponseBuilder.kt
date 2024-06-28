package dev.notypie.impl.command

import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.domain.command.dto.SlackEventContents

class SlackModalResponseBuilder: SlackResponseBuilder{
    override fun buildRequestBody(): SlackEventContents {
        TODO("Not yet implemented")
    }
}