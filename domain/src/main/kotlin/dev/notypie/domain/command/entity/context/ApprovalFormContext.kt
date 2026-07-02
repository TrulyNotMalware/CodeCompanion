package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage

internal class ApprovalFormContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType() = CommandDetailType.APPROVAL_REQUEST

    override fun runCommand(): CommandOutput {
        addOutbound(
            OutboundMessage.ChannelMessage(
                target = ConversationTarget(id = commandBasicInfo.channel),
                content =
                    MessageContent.Form(
                        headline = "Approve Form",
                        fields = buildSelectionFields(),
                        reason = null,
                        approval = null,
                    ),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
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
