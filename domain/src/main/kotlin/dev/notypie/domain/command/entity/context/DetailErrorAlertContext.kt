package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.UUID

internal class DetailErrorAlertContext(
    slackCommandData: SlackCommandData,
    private val targetClassName: String,
    private val errorMessage: String,
    private val details: String?,
    events: EventQueue<CommandEvent<EventPayload>>,
    slackEventBuilder: SlackEventBuilder,
    idempotencyKey: UUID,
) : CommandContext<NoSubCommands>(
        requestHeaders = slackCommandData.rawHeader,
        slackEventBuilder = slackEventBuilder,
        commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
        events = events,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        val event =
            slackEventBuilder.detailErrorTextRequest(
                errorClassName = targetClassName,
                errorMessage = errorMessage,
                details = details,
                commandType = commandType,
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
            )
        addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload, commandType = commandType)
    }
}
