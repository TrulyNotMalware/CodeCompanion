package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.history.entity.Status

internal class ApprovalCallbackContext(
    commandBasicInfo: CommandBasicInfo,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    approvalContents: ApprovalContents? = null,
    private val participants: Set<String> = emptySet(),
) : CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo
){
    private val approvalContents: ApprovalContents = approvalContents ?:
        ApprovalContents(
            reason = "approve requests", idempotencyKey = this.commandBasicInfo.idempotencyKey,
            commandDetailType = this.commandDetailType
        )

    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTICE_FORM

    override fun runCommand(): CommandOutput {
        val results = this.participants.map {
                participant -> this.slackApiRequester.simpleApplyRejectRequest(
                    commandDetailType = this.commandDetailType,
                    approvalContents = this.approvalContents, commandBasicInfo = this.commandBasicInfo,
                    commandType = this.commandType, targetUserId = participant
            )
        }
        val isAllOk = results.all{ it.ok }
        val status =
            if (results.map { it.status }.all { it == Status.SUCCESS || it == Status.IN_PROGRESSED })
            Status.SUCCESS else Status.FAILED
        return CommandOutput(
            ok = isAllOk,
            status = status,
            apiAppId = this.commandBasicInfo.appId,
            idempotencyKey = this.commandBasicInfo.idempotencyKey,
            publisherId = this.commandBasicInfo.publisherId,
            channel = this.commandBasicInfo.channel,
            token = this.commandBasicInfo.appToken,
            commandType = this.commandType,
            actionStates = results.flatMap { it.actionStates },
            commandDetailType = this.commandDetailType
        )
    }


    // interaction 은 replace 해주어야 함. 승인, 거절 여부
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {

        return this.interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
    }
}