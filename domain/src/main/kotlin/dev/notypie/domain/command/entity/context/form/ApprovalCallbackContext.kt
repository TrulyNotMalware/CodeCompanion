package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.history.entity.Status

internal class ApprovalCallbackContext(
    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    approvalContents: ApprovalContents? = null,
    events: EventQueue<CommandEvent<EventPayload>>,
    private val participants: Set<String> = emptySet(),
) : ReactionContext(
    slackEventBuilder = slackEventBuilder,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo,
    events = events,
){
    private val approvalContents: ApprovalContents = approvalContents ?:
        ApprovalContents(
            reason = "approve requests", idempotencyKey = this.commandBasicInfo.idempotencyKey,
            commandDetailType = this.commandDetailType, publisherId = this.commandBasicInfo.publisherId
        )

    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTICE_FORM

    override fun runCommand() = this.handleCommand()
    override fun runCommand(commandDetailType: CommandDetailType) =
        this.handleCommand(commandDetailType = commandDetailType)

    override fun handleInteraction(interactionPayload: InteractionPayload) =
        this.interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)

    private fun handleCommand(commandDetailType: CommandDetailType = this.commandDetailType): CommandOutput {
        val results = this.sendNoticeToParticipants(commandDetailType = commandDetailType)
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

    private fun sendNoticeToParticipants(commandDetailType: CommandDetailType = this.commandDetailType) =
        this.participants.map { participant ->
            val event = this.slackEventBuilder.simpleApplyRejectRequest(
                commandDetailType = commandDetailType,
                approvalContents = this.approvalContents, commandBasicInfo = this.commandBasicInfo,
                commandType = this.commandType, targetUserId = participant )
            this.addNewEvent(commandEvent = event)
            CommandOutput.success(payload = event.payload)
        }
}