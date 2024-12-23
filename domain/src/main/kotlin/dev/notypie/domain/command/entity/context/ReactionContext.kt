package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.withNewKey

internal abstract class ReactionContext(
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo
): CommandContext(
    commandBasicInfo = commandBasicInfo.withNewKey(),
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester
)