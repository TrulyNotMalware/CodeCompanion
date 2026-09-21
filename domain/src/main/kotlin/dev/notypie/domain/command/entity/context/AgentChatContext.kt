package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

internal class AgentChatContext(
    private val prompt: String,
    private val threadId: String?,
    private val requesterName: String,
    private val channelName: String,
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        subCommand = SubCommand.empty(),
    ) {
    companion object {
        internal const val EMPTY_PROMPT_MESSAGE: String =
            "Nothing to ask — try `@CodeCompanion ask <question>`."
    }

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.AGENT_CONVERSE

    override fun runCommand(): CommandOutput {
        if (prompt.isBlank()) return createErrorResponse(errMessage = EMPTY_PROMPT_MESSAGE)
        addIntent(
            CommandIntent.AgentConverse(
                prompt = prompt,
                threadId = threadId,
                requesterName = requesterName,
                channelName = channelName,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
