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
import dev.notypie.domain.command.outbound.channelRefusedNotice
import dev.notypie.domain.command.outbound.directFallbackOf
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

    private val channel: ConversationTarget = ConversationTarget(id = commandBasicInfo.channel)
    private val requester: UserRef = UserRef(id = commandBasicInfo.publisherId)

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.AGENT_ASK_QUESTION

    override fun runCommand(): CommandOutput {
        if (!actorRole.grants(permission = CommandPermission.AI)) return permissionDenied()
        if (prompt.isBlank()) return usageError()
        addOutbound(
            message =
                OutboundMessage.ChannelMessage(
                    target = channel,
                    content =
                        MessageContent.AgentQuestion(
                            prompt = prompt,
                            requesterName = requesterName,
                            channelName = channelName,
                        ),
                    detailType = commandDetailType,
                    fallback = channelRefusedNotice(channel = channel, recipient = requester),
                ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun permissionDenied(): CommandOutput {
        addOutbound(message = replyToRequester(markdown = PERMISSION_DENIED_MESSAGE))
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun usageError(): CommandOutput {
        addOutbound(message = replyToRequester(markdown = USAGE_MESSAGE))
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            reason = USAGE_MESSAGE,
        )
    }

    private fun replyToRequester(markdown: String): OutboundMessage.Ephemeral {
        val content = MessageContent.Text(headline = null, markdown = markdown)
        return OutboundMessage.Ephemeral(
            target = channel,
            recipient = requester,
            content = content,
            fallback = directFallbackOf(channel = channel, recipient = requester, content = content),
        )
    }
}
