package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType

internal class SlackErrorAlertContext(
    slackCommandData: SlackCommandData,
    private val targetClassName: String,
    private val errorMessage: String,
    private val details: String?,
    slackApiRequester: SlackApiRequester,
    idempotencyKey: String
) : CommandContext(
    requestHeaders = slackCommandData.rawHeader,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey)
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.errorTextRequest(
            errorClassName = targetClassName, channel = this.commandBasicInfo.channel,
            errorMessage = errorMessage, details = details,
            commandType = this.commandType,
            idempotencyKey = this.commandBasicInfo.idempotencyKey, commandDetailType = this.commandDetailType
        )
}