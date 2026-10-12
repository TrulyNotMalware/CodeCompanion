package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.slash.ASK_USAGE
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.common.escapeMarkup

internal class AskSlashContext(
    commandBasicInfo: CommandBasicInfo,
    private val prompt: String,
    private val requesterName: String,
    private val channelName: String,
    private val actorRole: UserRole,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    companion object {
        internal const val PERMISSION_DENIED_MESSAGE: String =
            "You don't have permission to use the AI assistant. Ask an admin to grant you access."
        internal val USAGE_MESSAGE: String = "Usage: `${ASK_USAGE.escapeMarkup()}`"
    }

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.AGENT_ASK_QUESTION

    override fun runCommand(): CommandOutput {
        if (!actorRole.grants(permission = CommandPermission.AI)) return permissionDenied()
        if (prompt.isBlank()) return createErrorResponse(errMessage = USAGE_MESSAGE)
        addOutbound(
            message =
                OutboundMessage.ChannelMessage(
                    target = ConversationTarget(id = commandBasicInfo.channel),
                    content =
                        MessageContent.AgentQuestion(
                            prompt = prompt,
                            requesterName = requesterName,
                            channelName = channelName,
                        ),
                    detailType = commandDetailType,
                ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun permissionDenied(): CommandOutput {
        addOutbound(
            message =
                OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = commandBasicInfo.channel),
                    recipient = UserRef(id = commandBasicInfo.publisherId),
                    content = MessageContent.Text(headline = null, markdown = PERMISSION_DENIED_MESSAGE),
                ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
