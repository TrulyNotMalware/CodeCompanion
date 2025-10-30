package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.ApprovalContentType
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import java.util.*

internal class RequestApprovalContext(
    private val users: Queue<String>,
    private val commands: Queue<String>,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    basicInfo: CommandBasicInfo,
    events: EventQueue<CommandEvent<EventPayload>>,
) : CommandContext<NoSubCommands>(
        slackEventBuilder = slackEventBuilder,
        requestHeaders = requestHeaders,
        commandBasicInfo = basicInfo,
        events = events,
        subCommand = SubCommand.empty(),
    ) {
    private val approvalContents: ApprovalContents = buildContents()

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType() = CommandDetailType.REQUEST_APPLY_FORM

    override fun runCommand(): CommandOutput {
        val event =
            slackEventBuilder.simpleApplyRejectRequest(
                approvalContents = approvalContents,
                commandType = commandType,
                commandDetailType = commandDetailType,
                commandBasicInfo = commandBasicInfo,
            )
        addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload, commandType = commandType)
    }

    // FIXME Changed to receive input from Modal. 7.15 test for approval button.
    private fun buildContents(type: ApprovalContentType = ApprovalContentType.SIMPLE_REQUEST_FORM): ApprovalContents {
        val command = commands.poll()
        return ApprovalContents(
            type = type,
            reason = command,
            idempotencyKey = commandBasicInfo.idempotencyKey,
            commandDetailType = commandDetailType,
            publisherId = commandBasicInfo.publisherId,
        )
    }
}
