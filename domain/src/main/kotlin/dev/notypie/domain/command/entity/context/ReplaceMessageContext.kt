package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue

internal class ReplaceMessageContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackApiRequester: SlackApiRequester,
    events: Queue<CommandEvent<EventPayload>>,
    private val responseUrl: String,
    private val markdownMessage: String
): CommandContext(
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = commandBasicInfo,
    events = events
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput = replaceText()
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput = replaceText()

    /**
     * Replaces the original text of a Slack message with the specified markdown content.
     *
     * @return A SlackApiResponse indicating the result of the replace text operation.
     */
    private fun replaceText(): CommandOutput =
        this.slackApiRequester.replaceOriginalText(
            markdownText = this.markdownMessage,
            responseUrl = this.responseUrl,
            commandBasicInfo = this.commandBasicInfo,
            commandDetailType = this.commandDetailType,
            commandType = this.commandType
        )
}