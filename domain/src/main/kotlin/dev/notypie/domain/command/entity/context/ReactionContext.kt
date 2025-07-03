package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.withNewKey
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue

internal abstract class ReactionContext(
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    events: Queue<CommandEvent<EventPayload>>
): CommandContext(
    commandBasicInfo = commandBasicInfo.withNewKey(),
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester,
    events = events
)