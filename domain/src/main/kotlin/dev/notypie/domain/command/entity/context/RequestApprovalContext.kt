package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.ApprovalContentType
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.*

internal class RequestApprovalContext(
    private val users: Queue<String>,
    private val commands: Queue<String>,

    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    basicInfo: CommandBasicInfo,
    events: Queue<CommandEvent<EventPayload>>
): CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = basicInfo,
    events = events,
){

    private val approvalContents: ApprovalContents = this.buildContents()

    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType() = CommandDetailType.REQUEST_APPLY_FORM

    override fun runCommand(): CommandOutput
        = this.slackApiRequester.simpleApplyRejectRequest(
            approvalContents = this.approvalContents,
            commandType = this.commandType, commandDetailType = this.commandDetailType,
            commandBasicInfo = this.commandBasicInfo,
        )


    private fun buildContents(type: ApprovalContentType = ApprovalContentType.SIMPLE_REQUEST_FORM): ApprovalContents{//FIXME Changed to receive input from Modal. 7.15 test for approval button.
        val command = this.commands.poll()
        return ApprovalContents(
            type = type,
            reason = command, idempotencyKey = this.commandBasicInfo.idempotencyKey,
            commandDetailType = this.commandDetailType, publisherId = commandBasicInfo.publisherId
        )
    }
}