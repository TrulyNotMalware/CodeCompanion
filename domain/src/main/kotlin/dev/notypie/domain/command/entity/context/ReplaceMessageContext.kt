package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal class ReplaceMessageContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackEventBuilder: SlackEventBuilder,
    events: EventQueue<CommandEvent<EventPayload>>,
    subCommand: SubCommand,
    private val responseUrl: String,
    private val markdownMessage: String
): CommandContext(
    requestHeaders = requestHeaders,
    slackEventBuilder = slackEventBuilder,
    commandBasicInfo = commandBasicInfo,
    events = events,
    subCommand = subCommand,
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput = replaceText()
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput = replaceText()

    /**
     * Replaces the original text of a Slack message with the specified Markdown content.
     *
     * @return A SlackApiResponse indicating the result of the replace text operation.
     */
    private fun replaceText(): CommandOutput {
        val event = this.slackEventBuilder.replaceOriginalText(
            markdownText = this.markdownMessage,
            responseUrl = this.responseUrl,
            commandBasicInfo = this.commandBasicInfo,
            commandDetailType = this.commandDetailType,
            commandType = this.commandType
        )
        this.addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload)
    }
}