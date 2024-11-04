package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

abstract class CommandContext(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val slackApiRequester: SlackApiRequester,
) {
    val commandType: CommandType = this.parseCommandType()
    val commandDetailType: CommandDetailType = this.parseCommandDetailType()

    internal abstract fun parseCommandType(): CommandType
    internal abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): SlackApiResponse = this.slackApiRequester.doNothing()
    internal open fun handleInteraction(interactionPayload: InteractionPayload): SlackApiResponse =
        this.slackApiRequester.doNothing()

    internal fun createErrorResponse(errorMessage: String): SlackApiResponse =
        EphemeralTextResponse(
            commandBasicInfo = this.commandBasicInfo, requestHeaders = this.requestHeaders,
            slackApiRequester = this.slackApiRequester, textMessage = errorMessage
        ).runCommand()
}