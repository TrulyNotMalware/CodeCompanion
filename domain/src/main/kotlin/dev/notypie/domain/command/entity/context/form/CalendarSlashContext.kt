package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.CALENDAR_USAGE
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage

private const val CONNECT_ACTION = "connect"
private const val DISCONNECT_ACTION = "disconnect"
private const val STATUS_ACTION = "status"

internal class CalendarSlashContext(
    commandBasicInfo: CommandBasicInfo,
    private val arguments: List<String>,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CALENDAR_CONNECTION

    override fun runCommand(): CommandOutput {
        val tokens = arguments.filter { argument -> argument.isNotBlank() }
        val usage = "Usage: $CALENDAR_USAGE"
        if (tokens.size != 1) return usageError(message = usage)
        val userId = commandBasicInfo.publisherId
        val intent =
            when (tokens.single().lowercase()) {
                CONNECT_ACTION -> CommandIntent.CalendarConnect(userId = userId)
                DISCONNECT_ACTION -> CommandIntent.CalendarDisconnect(userId = userId)
                STATUS_ACTION -> CommandIntent.CalendarStatus(userId = userId)
                else -> return usageError(message = "Unknown action '${tokens.single()}'. $usage")
            }
        addIntent(intent = intent)
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun usageError(message: String): CommandOutput {
        addOutbound(
            message =
                OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = commandBasicInfo.channel),
                    recipient = null,
                    content = MessageContent.Text(headline = null, markdown = message),
                ),
        )
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
            reason = message,
        )
    }
}
