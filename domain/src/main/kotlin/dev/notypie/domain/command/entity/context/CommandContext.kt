package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

internal abstract class CommandContext(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val slackApiRequester: SlackApiRequester,
) {
    val commandType: CommandType = this.parseCommandType()
    val commandDetailType: CommandDetailType = this.parseCommandDetailType()

    internal abstract fun parseCommandType(): CommandType
    internal abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): CommandOutput = CommandOutput.empty()
    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()
    internal open fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput =
        CommandOutput.empty()

    internal fun createErrorResponse(errMessage: String): CommandOutput =
        EphemeralTextResponse(
            commandBasicInfo = this.commandBasicInfo, requestHeaders = this.requestHeaders,
            slackApiRequester = this.slackApiRequester, textMessage = errMessage
        ).runCommand()

    internal fun createErrorResponse(errMessage: String, results: CommandOutput): CommandOutput {
        EphemeralTextResponse(
            commandBasicInfo = this.commandBasicInfo, requestHeaders = this.requestHeaders,
            slackApiRequester = this.slackApiRequester, textMessage = errMessage
        ).runCommand()
        return results
    }

    internal fun interactionSuccessResponse(responseUrl: String, mkdMessage: String = "Successfully processed."): CommandOutput =
        ReplaceMessageContext(
            commandBasicInfo = this.commandBasicInfo, requestHeaders = this.requestHeaders,
            slackApiRequester = this.slackApiRequester, responseUrl = responseUrl,
            markdownMessage = mkdMessage
        ).runCommand()

    internal fun interactionSuccessResponse(responseUrl: String,
                                            mkdMessage: String = "Successfully processed.",
                                            results :CommandOutput): CommandOutput {
        ReplaceMessageContext(
            commandBasicInfo = this.commandBasicInfo, requestHeaders = this.requestHeaders,
            slackApiRequester = this.slackApiRequester, responseUrl = responseUrl,
            markdownMessage = mkdMessage
        ).runCommand()
        return results
    }
}