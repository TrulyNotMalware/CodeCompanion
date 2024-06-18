package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.entity.CommandContext

class SlackChallengeContext(
    urlVerificationRequest: UrlVerificationRequest
): CommandContext(
    channel = urlVerificationRequest.channel,
    appToken = urlVerificationRequest.token,
    requestHeaders = SlackRequestHeaders(),
    tracking = false
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