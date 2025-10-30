package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload

internal class SlackApprovalFormContext(
    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    events: EventQueue<CommandEvent<EventPayload>>,
) : CommandContext<NoSubCommands>(
        slackEventBuilder = slackEventBuilder,
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        events = events,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType() = CommandDetailType.APPROVAL_FORM

    override fun runCommand(): CommandOutput {
        val event =
            slackEventBuilder.simpleApprovalFormRequest(
                headLineText = "Approve Form",
                selectionFields = buildSelectionFields(),
                commandType = commandType,
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
            )
        addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload, commandType = commandType)
    }

    private fun buildSelectionFields(): List<SelectionContents> =
        listOf(
            SelectionContents(
                title = "Purpose",
                explanation = "Please select the purpose of this form.",
                placeholderText = "SELECT",
                contents =
                    listOf(
                        SelectBoxDetails(name = "Pull Requests", value = "GIT_PULL_REQUEST"),
                        SelectBoxDetails(name = "Logs", value = "GET_LOGS"),
                    ),
            ),
        )
}
