package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

internal abstract class CommandContext<T : SubCommandDefinition>(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val subCommand: SubCommand<T>,
    val intents: IntentQueue,
) {
    val commandType: CommandType by lazy { parseCommandType() }
    val commandDetailType: CommandDetailType by lazy { parseCommandDetailType() }

    protected abstract fun parseCommandType(): CommandType

    protected abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): CommandOutput = CommandOutput.empty()

    protected fun createErrorResponse(errMessage: String): CommandOutput {
        addIntent(intent = CommandIntent.EphemeralResponse(message = errMessage))
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            reason = errMessage,
        )
    }

    protected fun createErrorResponse(errMessage: String, results: CommandOutput): CommandOutput {
        addIntent(intent = CommandIntent.EphemeralResponse(message = errMessage))
        return results
    }

    protected fun addIntent(intent: CommandIntent) {
        intents.offer(intent)
    }
}
