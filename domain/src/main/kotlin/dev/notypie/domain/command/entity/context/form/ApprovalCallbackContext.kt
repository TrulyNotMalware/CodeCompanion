package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.history.entity.Status

internal class ApprovalCallbackContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    approvalContents: ApprovalContents? = null,
    private val participants: Set<String> = emptySet(),
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    private val approvalContents: ApprovalContents = approvalContents ?: createDefaultApprovalContents()

    private fun createDefaultApprovalContents() =
        ApprovalContents(
            reason = "approve requests",
            idempotencyKey = commandBasicInfo.idempotencyKey,
            commandDetailType = commandDetailType,
            publisherId = commandBasicInfo.publisherId,
        )

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTICE_FORM

    override fun runCommand() = handleCommand()

    override fun runCommand(commandDetailType: CommandDetailType) = handleCommand(commandDetailType = commandDetailType)

    private fun handleCommand(commandDetailType: CommandDetailType = this.commandDetailType): CommandOutput {
        val results = sendNoticeToParticipants(commandDetailType = commandDetailType)
        val isAllOk = results.all { it.ok }
        val status =
            if (results.map { it.status }.all { it == Status.SUCCESS || it == Status.IN_PROGRESSED }) {
                Status.SUCCESS
            } else {
                Status.FAILED
            }
        return CommandOutput(
            ok = isAllOk,
            status = status,
            apiAppId = commandBasicInfo.appId,
            idempotencyKey = commandBasicInfo.idempotencyKey,
            publisherId = commandBasicInfo.publisherId,
            channel = commandBasicInfo.channel,
            token = commandBasicInfo.appToken,
            commandType = commandType,
            actionStates = results.flatMap { it.actionStates },
            commandDetailType = commandDetailType,
        )
    }

    /**
     * The intent's routing type is derived from [approvalContents.commandDetailType]
     * (single source of truth between the envelope and the Slack button value).
     * [commandDetailType] here is used only for [CommandOutput] metadata.
     */
    private fun sendNoticeToParticipants(
        commandDetailType: CommandDetailType = this.commandDetailType,
    ): List<CommandOutput> =
        participants.map { participant ->
            addIntent(
                CommandIntent.ApplyReject(
                    approvalContents = approvalContents,
                    targetUserId = participant,
                ),
            )
            CommandOutput.success(
                basicInfo = commandBasicInfo,
                commandType = commandType,
                commandDetailType = commandDetailType,
            )
        }
}
