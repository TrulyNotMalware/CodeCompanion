package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue

internal class SlackTextResponseContext(
    private val text: String,
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    slackApiRequester: SlackApiRequester,
    events: Queue<CommandEvent<EventPayload>>
): CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo,
    events = events,
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        return this.slackApiRequester.simpleTextRequest(
            commandBasicInfo = this.commandBasicInfo,
            headLineText = "Simple Text Response", simpleString = this.text,
            commandType = this.commandType,
            commandDetailType = this.commandDetailType
        )
    }
}