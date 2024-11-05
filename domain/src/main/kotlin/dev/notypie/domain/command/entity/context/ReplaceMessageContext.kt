package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

internal class ReplaceMessageContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackApiRequester: SlackApiRequester,
    private val responseUrl: String,
    private val markdownMessage: String
): CommandContext(
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = commandBasicInfo
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): SlackApiResponse = replaceText()
    override fun handleInteraction(interactionPayload: InteractionPayload): SlackApiResponse = replaceText()

    /**
     * Replaces the original text of a Slack message with the specified markdown content.
     *
     * @return A SlackApiResponse indicating the result of the replace text operation.
     */
    private fun replaceText(): SlackApiResponse =
        this.slackApiRequester.replaceOriginalText(
            markdownText = this.markdownMessage,
            responseUrl = this.responseUrl,
            commandBasicInfo = this.commandBasicInfo,
            commandDetailType = this.commandDetailType,
            commandType = this.commandType
        )
}