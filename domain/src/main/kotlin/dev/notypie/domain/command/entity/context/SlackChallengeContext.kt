package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackApiRequester

class SlackChallengeContext(
    urlVerificationRequest: UrlVerificationRequest,
    slackApiRequester: SlackApiRequester,
): CommandContext(
    channel = urlVerificationRequest.channel,
    appToken = urlVerificationRequest.token,
    requestHeaders = SlackRequestHeaders(),
    tracking = false,
    slackApiRequester = slackApiRequester
) {
    /**
     * Slack Challenge request is simple type.
     *
     * @return CommandType
     */
    override fun parseCommandType(): CommandType {
        return CommandType.SIMPLE
    }
}