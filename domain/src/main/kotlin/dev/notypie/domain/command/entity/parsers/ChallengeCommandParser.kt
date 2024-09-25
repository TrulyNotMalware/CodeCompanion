package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.EmptyContext

@Deprecated(message = "forRemoval")
class ChallengeCommandParser(
    private val requestHeaders: SlackRequestHeaders,
    private val urlVerificationRequest: UrlVerificationRequest,
    private val slackApiRequester: SlackApiRequester,
    val idempotencyKey: String
): ContextParser{
    override fun parseContext(idempotencyKey: String): CommandContext =
        EmptyContext(channel = this.urlVerificationRequest.channel,
            appToken = this.urlVerificationRequest.token, requestHeaders = this.requestHeaders
            ,slackApiRequester = this.slackApiRequester, idempotencyKey = this.idempotencyKey)
}