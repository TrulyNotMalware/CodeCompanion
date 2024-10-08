package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import java.util.LinkedList
import java.util.Queue

internal class SlackApprovalFormContext(
    channel: String,
    appToken: String,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    idempotencyKey: String
): CommandContext(
    channel = channel,
    appToken = appToken,
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    idempotencyKey = idempotencyKey
) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType() = CommandDetailType.APPROVAL_FORM

    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.simpleApprovalFormRequest(
            headLineText = "Approve Form", channel = this.channel,
            selectionFields = this.buildSelectionFields(), commandType = this.commandType,
            idempotencyKey = this.idempotencyKey, commandDetailType = this.commandDetailType
        )

    private fun buildSelectionFields(): List<SelectionContents> = listOf(
        SelectionContents(title = "Purpose", explanation = "Please select the purpose of this form.",
            placeholderText = "SELECT", contents = listOf(
                SelectBoxDetails(name = "Pull Requests", value = "GIT_PULL_REQUEST"),
                SelectBoxDetails(name = "Logs", value = "GET_LOGS")
            )
        )
    )

    override fun doWhenApproved(): CommandContext {
        val commandQueue: Queue<String> = LinkedList()
        val userQueue: Queue<String> = LinkedList()
        return RequestApprovalContext(
            slackApiRequester = this.slackApiRequester,
            appToken = this.appToken, channel = this.channel,
            idempotencyKey = this.idempotencyKey,
            users = userQueue,
            commands = commandQueue
        )
    }

}