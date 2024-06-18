package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.entity.CommandContext

class SlackAppMentionContext(
    val slackCommandData: SlackCommandData
): CommandContext(
    channel = slackCommandData.channel,
    appToken = slackCommandData.appToken,
    requestHeaders = slackCommandData.rawHeader
) {

    init{

    }

    override fun parseCommandType(): CommandType {
        return CommandType.SIMPLE
    }

    private fun getEventType(): String{
        return this.slackCommandData.rawBody["event"].toString()
    }
}