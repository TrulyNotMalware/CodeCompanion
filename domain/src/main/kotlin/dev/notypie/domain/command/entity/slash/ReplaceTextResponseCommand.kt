package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReplaceMessageContext

class ReplaceTextResponseCommand(
    idempotencyKey: String,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
    private val markdownMessage: String,
    private val responseUrl: String,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester
) {
    override fun parseContext(): CommandContext = ReplaceMessageContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
        requestHeaders = SlackRequestHeaders(),
        slackApiRequester = this.slackApiRequester, markdownMessage = markdownMessage,
        responseUrl = responseUrl
    )
}