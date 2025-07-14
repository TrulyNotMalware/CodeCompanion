package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.withNewKey
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal abstract class ReactionContext(
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    events: EventQueue<CommandEvent<EventPayload>>
): CommandContext(
    commandBasicInfo = commandBasicInfo.withNewKey(),
    requestHeaders = requestHeaders,
    slackEventBuilder = slackEventBuilder,
    events = events
)