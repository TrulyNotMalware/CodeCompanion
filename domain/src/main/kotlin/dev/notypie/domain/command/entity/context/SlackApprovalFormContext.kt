package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.entity.CommandType

class SlackApprovalFormContext(
    channel: String,
    appToken: String,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
): CommandContext(
    channel = channel,
    appToken = appToken,
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.simpleApprovalFormRequest(
            headLineText = "Approve Form", channel = this.channel,
            selectionFields = this.buildSelectionFields()
        )

    //FIXME Test for interaction commands.
    private fun buildSelectionFields(): List<SelectionContents> = listOf(
        SelectionContents(title = "Purpose", explanation = "Please select the purpose of this form.",
            placeholderText = "SELECT", contents = listOf(
                SelectBoxDetails(name = "Pull Requests", value = "GIT_PULL_REQUEST"),
                SelectBoxDetails(name = "Logs", value = "GET_LOGS")
            )
        )
    )

}