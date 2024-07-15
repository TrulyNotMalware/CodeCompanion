package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandContext

class SlackErrorAlertContext(
    private val slackCommandData: SlackCommandData,
    private val targetClassName: String,
    private val errorMessage: String,
    private val details: String?,

    slackApiRequester: SlackApiRequester
) : CommandContext(
    channel = slackCommandData.channel,
    appToken = slackCommandData.appToken,
    requestHeaders = slackCommandData.rawHeader,
    slackApiRequester = slackApiRequester
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.errorTextRequest(
            errorClassName = targetClassName, channel = this.channel,
            errorMessage = errorMessage, details = details
        )
}