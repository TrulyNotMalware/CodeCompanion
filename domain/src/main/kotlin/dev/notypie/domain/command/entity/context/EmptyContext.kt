package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue

internal class EmptyContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackApiRequester: SlackApiRequester,
    events: Queue<CommandEvent<EventPayload>>
): CommandContext(
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = commandBasicInfo,
    events = events
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.NOTHING
}