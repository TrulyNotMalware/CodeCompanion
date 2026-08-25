package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage

internal abstract class CommandContext<T : SubCommandDefinition>(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val subCommand: SubCommand<T>,
    val intents: IntentQueue,
) {
    val commandType: CommandType by lazy { parseCommandType() }
    val commandDetailType: CommandDetailType by lazy { parseCommandDetailType() }

    protected abstract fun parseCommandType(): CommandType

    protected abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): CommandOutput = CommandOutput.empty()

    protected fun createErrorResponse(errMessage: String): CommandOutput {
        addOutbound(message = errorEphemeral(errMessage = errMessage))
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            reason = errMessage,
        )
    }

    protected fun createErrorResponse(errMessage: String, results: CommandOutput): CommandOutput {
        addOutbound(message = errorEphemeral(errMessage = errMessage))
        return results
    }

    private fun errorEphemeral(errMessage: String): OutboundMessage.Ephemeral =
        OutboundMessage.Ephemeral(
            target = ConversationTarget(id = commandBasicInfo.channel),
            recipient = null,
            content = MessageContent.Text(headline = null, markdown = errMessage),
        )

    protected fun addIntent(intent: CommandIntent) {
        intents.offer(intent)
    }

    protected fun addOutbound(message: OutboundMessage) {
        intents.offer(message)
    }
}
