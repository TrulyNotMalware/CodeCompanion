package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.mention.Element
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.dto.mention.extractText
import dev.notypie.domain.command.entity.CommandSet
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.SlackNoticeContext
import dev.notypie.domain.command.entity.context.SlackStatusContext
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.intent.IntentQueue
import java.util.*

internal class AppMentionContextParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val intents: IntentQueue,
) : ContextParser {
    companion object {
        const val BLOCK_TYPE_RICH_TEXT = "rich_text"
        const val ELEMENT_TYPE_TEXT_SECTION = "rich_text_section"

        const val ELEMENT_TYPE_USER = "user"
        const val ELEMENT_TYPE_TEXT = "text"

        const val COMMAND_DELIMITER = " "

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
            """.trimIndent()
    }

    private val slackAppMentionRequestData: SlackEventCallBackRequest by lazy {
        slackCommandData.body as? SlackEventCallBackRequest
            ?: throw IllegalArgumentException("Invalid body type for AppMention")
    }
    private val botId: String by lazy { slackAppMentionRequestData.authorizations.find { it.isBot }?.userId ?: "" }

    override fun parseContext(idempotencyKey: UUID): CommandContext<NoSubCommands> =
        slackAppMentionRequestData.event.blocks
            .find { blocks -> blocks.elements.isNotEmpty() && blocks.type == BLOCK_TYPE_RICH_TEXT }
            ?.elements
            ?.find { element -> element.type == ELEMENT_TYPE_TEXT_SECTION }
            ?.let { extractUserAndCommand(elements = it.elements) }
            ?.let { buildContext(userQueue = it.first, commandQueue = it.second) }
            ?: handleNotSupportedCommand()

    private fun handleNotSupportedCommand(): SlackTextResponseContext =
        SlackTextResponseContext(
            requestHeaders = slackCommandData.rawHeader,
            text = "Command Not supported.",
            commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun extractUserAndCommand(elements: List<Element>?): Pair<Queue<String>, Queue<String>> {
        val userQueue: Queue<String> = LinkedList()
        val commandQueue: Queue<String> = LinkedList()
        elements
            ?.forEach { element ->
                when (element.type) {
                    ELEMENT_TYPE_USER -> {
                        if (element.userId != botId) userQueue.offer(element.userId)
                    }

                    ELEMENT_TYPE_TEXT -> {
                        element
                            .extractText()
                            ?.split(COMMAND_DELIMITER)
                            ?.filter { it.isNotBlank() }
                            ?.forEach { commandQueue.offer(it) }
                    }
                }
            }

        verifyCommandQueue(commandQueue = commandQueue)
        return userQueue to commandQueue
    }

    private fun buildContext(userQueue: Queue<String>, commandQueue: Queue<String>): CommandContext<NoSubCommands> {
        val command: String = commandQueue.poll().replace(" ", "")
        return when (CommandSet.parseCommand(command)) { // FIXME Later when block
            CommandSet.NOTICE -> {
                SlackNoticeContext(
                    users = userQueue,
                    commands = commandQueue,
                    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    requestHeaders = slackCommandData.rawHeader,
                    intents = intents,
                )
            }

            CommandSet.APPROVAL -> {
                SlackApprovalFormContext(
                    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    requestHeaders = slackCommandData.rawHeader,
                    intents = intents,
                )
            }

            CommandSet.HELP -> {
                SlackTextResponseContext(
                    text = HELP_MESSAGE,
                    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    requestHeaders = slackCommandData.rawHeader,
                    intents = intents,
                )
            }

            CommandSet.STATUS -> {
                SlackStatusContext(
                    commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    requestHeaders = slackCommandData.rawHeader,
                    intents = intents,
                )
            }

            CommandSet.UNKNOWN -> {
                DetailErrorAlertContext(
                    slackCommandData = slackCommandData,
                    errorMessage = "Command \"$command\" not found",
                    targetClassName = this::class.simpleName ?: "SlackAppMentionContext",
                    details = null,
                    idempotencyKey = idempotencyKey,
                    intents = intents,
                )
            }
        }
    }

    private fun verifyCommandQueue(commandQueue: Queue<String>) {
        if (commandQueue.isEmpty()) throw IllegalArgumentException("Command Queue is empty")
    }
}
