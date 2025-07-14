package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.UUID

internal class DetailErrorAlertContext(
    slackCommandData: SlackCommandData,
    private val targetClassName: String,
    private val errorMessage: String,
    private val details: String?,
    events : EventQueue<CommandEvent<EventPayload>>,
    slackEventBuilder: SlackEventBuilder,
    idempotencyKey: UUID
) : CommandContext(
    requestHeaders = slackCommandData.rawHeader,
    slackEventBuilder = slackEventBuilder,
    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
    events = events
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        val event = this.slackEventBuilder.detailErrorTextRequest(
            errorClassName = targetClassName,
            errorMessage = errorMessage, details = details,
            commandType = this.commandType,
            commandBasicInfo = this.commandBasicInfo, commandDetailType = this.commandDetailType
        )
        this.addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload)
    }
}