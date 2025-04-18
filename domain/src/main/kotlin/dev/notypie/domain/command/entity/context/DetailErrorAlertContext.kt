package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import java.util.UUID

internal class DetailErrorAlertContext(
    slackCommandData: SlackCommandData,
    private val targetClassName: String,
    private val errorMessage: String,
    private val details: String?,
    slackApiRequester: SlackApiRequester,
    idempotencyKey: UUID
) : CommandContext(
    requestHeaders = slackCommandData.rawHeader,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey)
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput =
        this.slackApiRequester.detailErrorTextRequest(
            errorClassName = targetClassName,
            errorMessage = errorMessage, details = details,
            commandType = this.commandType,
            commandBasicInfo = this.commandBasicInfo, commandDetailType = this.commandDetailType
        )
}