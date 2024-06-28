package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.domain.command.SlackRequestHandler

class SlackChallengeContext(
    urlVerificationRequest: UrlVerificationRequest,
    responseBuilder: SlackResponseBuilder,
    requestHandler: SlackRequestHandler
): CommandContext(
    channel = urlVerificationRequest.channel,
    appToken = urlVerificationRequest.token,
    requestHeaders = SlackRequestHeaders(),
    tracking = false,
    responseBuilder = responseBuilder,
    requestHandler = requestHandler
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