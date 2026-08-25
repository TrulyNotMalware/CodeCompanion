package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Context for `@bot ask <prompt>` mentions (and unmatched free-text mentions routed here as a
 * fallback). Emits a [CommandIntent.AgentConverse] which the resolver lifts to an internal
 * [dev.notypie.domain.command.entity.event.AgentConverseRequestEvent]. The AI turn itself runs in
 * an async application listener — the LLM call is a slow external network call, so the domain only
 * records the intent; it never talks to the agent backend (same layering rule as [StatusContext]).
 *
 * [threadId] anchors the conversation: `thread ?: message` of the mention, so a top-level mention
 * starts a thread at itself and follow-up mentions inside that thread continue the same session.
 */
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
