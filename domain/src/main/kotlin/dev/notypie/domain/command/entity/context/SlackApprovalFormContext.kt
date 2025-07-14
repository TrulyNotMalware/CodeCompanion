package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal class SlackApprovalFormContext(
    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    events: EventQueue<CommandEvent<EventPayload>>
): CommandContext(
    slackEventBuilder = slackEventBuilder,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo,
    events = events,
) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType() = CommandDetailType.APPROVAL_FORM

    override fun runCommand(): CommandOutput {
        val event = this.slackEventBuilder.simpleApprovalFormRequest(
            headLineText = "Approve Form",
            selectionFields = this.buildSelectionFields(), commandType = this.commandType,
            commandBasicInfo = this.commandBasicInfo, commandDetailType = this.commandDetailType
        )
        this.addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload)
    }

    private fun buildSelectionFields(): List<SelectionContents> = listOf(
        SelectionContents(title = "Purpose", explanation = "Please select the purpose of this form.",
            placeholderText = "SELECT", contents = listOf(
                SelectBoxDetails(name = "Pull Requests", value = "GIT_PULL_REQUEST"),
                SelectBoxDetails(name = "Logs", value = "GET_LOGS")
            )
        )
    )

}