package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.entity.CommandSet
import dev.notypie.domain.command.entity.context.AgentChatContext
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.NoticeContext
import dev.notypie.domain.command.entity.context.StatusContext
import dev.notypie.domain.command.entity.context.TextResponseContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.intent.IntentQueue
import java.util.*

internal class AppMentionContextParser(
    private val commandData: InboundCommand,
    private val mention: MentionInvocation,
    val idempotencyKey: UUID,
    private val intents: IntentQueue,
) : ContextParser {
    companion object {
        // Markdown body for `@bot help`. Listed once here so the parser test can pin the
        // exact wording — drift between the docs and the runtime help message is the most
        // common bug we see when help text gets edited in passing.
        internal val HELP_MESSAGE: String =
            """
            *CodeCompanion — quick reference*

            *Slash commands*
            • `/meetup` — open the new-meeting form
            • `/meetup list` — show your upcoming meetings (host-owned rows have an inline *Cancel* button)
            • `/meetup list today|tomorrow|week|month` — filter by window

            *Mentions*
            • `@CodeCompanion notice @user1 @user2 <message>` — send a notice
            • `@CodeCompanion approval` — open the request-approval form
            • `@CodeCompanion help` — show this help
            • `@CodeCompanion status` — show outbox lag and in-flight counts
            • `@CodeCompanion ask <question>` — ask the AI assistant (replies in a thread; mention again in the thread to continue)

            Anything that isn't a command above is treated as `ask`.
            """.trimIndent()
    }

    override fun parseContext(idempotencyKey: UUID): CommandContext<NoSubCommands> {
        if (!mention.hasCommandStructure) return handleNotSupportedCommand()
        if (mention.commandTokens.isEmpty()) throw IllegalArgumentException("Command Queue is empty")
        val command: String = mention.commandTokens.first().replace(" ", "")
        return when (CommandSet.parseCommand(command)) {
            CommandSet.NOTICE -> {
                NoticeContext(
                    users = LinkedList(mention.mentionedUserIds),
                    commands = LinkedList(mention.commandTokens.drop(1)),
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.APPROVAL -> {
                ApprovalFormContext(
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.HELP -> {
                TextResponseContext(
                    text = HELP_MESSAGE,
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.STATUS -> {
                StatusContext(
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.ASK -> agentChatContext(promptTokens = mention.commandTokens.drop(1))

            // Free-text fallback: any mention that doesn't match a command is a question for the
            // AI assistant, keyword included ("what does status mean" must not lose "what").
            CommandSet.UNKNOWN -> agentChatContext(promptTokens = mention.commandTokens)
        }
    }

    private fun agentChatContext(promptTokens: List<String>): AgentChatContext =
        AgentChatContext(
            prompt = promptTokens.joinToString(separator = " "),
            // A top-level mention anchors its own thread; a threaded mention continues that thread.
            threadId = (mention.thread ?: mention.message)?.raw,
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun handleNotSupportedCommand(): TextResponseContext =
        TextResponseContext(
            text = "Command Not supported.",
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )
}
