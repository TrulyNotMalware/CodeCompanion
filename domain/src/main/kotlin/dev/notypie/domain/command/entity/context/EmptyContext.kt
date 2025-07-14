package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal class EmptyContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackEventBuilder: SlackEventBuilder,
    events: EventQueue<CommandEvent<EventPayload>>
): CommandContext(
    requestHeaders = requestHeaders,
    slackEventBuilder = slackEventBuilder,
    commandBasicInfo = commandBasicInfo,
    events = events
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.NOTHING
}