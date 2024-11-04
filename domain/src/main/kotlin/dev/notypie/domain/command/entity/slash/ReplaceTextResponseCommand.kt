package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.ReplaceMessageContext

class ReplaceTextResponseCommand(
    idempotencyKey: String,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
    markdownMessage: String,
    responseUrl: String,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester
) {
    private val context = ReplaceMessageContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
        requestHeaders = SlackRequestHeaders(),
        slackApiRequester = this.slackApiRequester, markdownMessage = markdownMessage,
        responseUrl = responseUrl
    )
    override fun handleEvent(): SlackApiResponse = this.context.runCommand()
}