package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.SlackApiResponse
import java.util.*

class SlackApprovalContext(
    private val users: Queue<String>,
    private val commands: Queue<String>,

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
){
    companion object{
    }

    private val approvalContents: ApprovalContents = this.buildContents()

    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun runCommand(): SlackApiResponse
        = this.slackApiRequester.simpleApplyRejectRequest(
            headLineText = "Approval Requests!", channel = this.channel, approvalContents = this.approvalContents,
            commandType = this.commandType)


    private fun buildContents(): ApprovalContents{//FIXME Changed to receive input from Modal. 7.15 test for approval button.
        val command = this.commands.poll()
        return ApprovalContents(
            type = "DEFAULT", reason = command,
            approvalInteractionValue = "Approved", rejectInteractionValue = "Rejected"
        )
    }
}