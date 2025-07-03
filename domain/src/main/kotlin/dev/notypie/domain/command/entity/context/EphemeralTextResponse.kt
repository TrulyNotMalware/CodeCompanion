package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue

internal class EphemeralTextResponse(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackApiRequester: SlackApiRequester,
    events: Queue<CommandEvent<EventPayload>>,
    private val textMessage: String,
): CommandContext(
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = commandBasicInfo,
    events = events
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput =
        this.slackApiRequester.simpleEphemeralTextRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandDetailType = this.commandDetailType,
            commandType = this.commandType,
            textMessage = this.textMessage
        )

}