package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.entity.CommandSet
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.mention.Element
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.mention.PlainText
import dev.notypie.domain.command.dto.mention.TextObject
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.entity.context.SlackNoticeContext
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.*

internal class AppMentionCommandParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val slackApiRequester: SlackApiRequester,
    events: Queue<CommandEvent<EventPayload>>
): ContextParser {
    companion object{
        const val BLOCK_TYPE_RICH_TEXT = "rich_text"
        const val ELEMENT_TYPE_TEXT_SECTION = "rich_text_section"

        const val ELEMENT_TYPE_USER = "user"
        const val ELEMENT_TYPE_TEXT = "text"

        const val COMMAND_DELIMITER = " "
    }
    private val slackAppMentionRequestData: SlackEventCallBackRequest
    private val botId: String
    private val parsedContext: CommandContext

    init{
        this.slackAppMentionRequestData = this.slackCommandData.body as SlackEventCallBackRequest
        this.botId = slackAppMentionRequestData.authorizations.find { it.isBot }?.userId ?: ""
        this.parsedContext = this.parseContext(events = events, idempotencyKey = idempotencyKey)
    }

    override fun parseContext(events: Queue<CommandEvent<EventPayload>>, idempotencyKey: UUID): CommandContext =
        this.slackAppMentionRequestData.event.blocks
        .find { blocks -> blocks.elements.isNotEmpty() && blocks.type == BLOCK_TYPE_RICH_TEXT }
        ?.elements?.find { element -> element.type == ELEMENT_TYPE_TEXT_SECTION }
        ?.let { this.extractUserAndCommand(elements = it.elements) }
        ?.let { this.buildContext(events = events, userQueue = it.first, commandQueue = it.second) }
        ?: this.handleNotSupportedCommand(events = events)

    private fun handleNotSupportedCommand(
        events: Queue<CommandEvent<EventPayload>>
    ): SlackTextResponseContext = SlackTextResponseContext(
        requestHeaders = this.slackCommandData.rawHeader,
        slackApiRequester = this.slackApiRequester, text = "Command Not supported.",
        commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
        events = events
    )

    private fun extractUserAndCommand(elements : List<Element>?):
            Pair<Queue<String>, Queue<String>> {
        val userQueue: Queue<String> = LinkedList()
        val commandQueue: Queue<String> = LinkedList()
        elements
            ?.forEach { element ->
                when (element.type) {
                    ELEMENT_TYPE_USER -> if (element.userId != this.botId) userQueue.offer(element.userId)
                    ELEMENT_TYPE_TEXT -> {
                        val text = when( val textValue = element.text ) {
                            is PlainText -> textValue.value
                            is TextObject -> textValue.text
                            else -> null
                        }
                        text?.split(COMMAND_DELIMITER)?.forEach { if(it.isNotBlank()) commandQueue.offer(it) }
                    }
                }
            }
        this.verifyCommandQueue(commandQueue = commandQueue)
        return userQueue to commandQueue
    }

    private fun buildContext(events: Queue<CommandEvent<EventPayload>>, userQueue: Queue<String>, commandQueue: Queue<String>): CommandContext {
        val command: String = commandQueue.poll().replace(" ", "")
        return when(CommandSet.parseCommand(command)){ //FIXME Later when block
            CommandSet.NOTICE -> SlackNoticeContext(
                    users = userQueue, commands = commandQueue,
                commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                requestHeaders = this.slackCommandData.rawHeader, slackApiRequester = this.slackApiRequester,
                events = events
            )
            CommandSet.APPROVAL -> SlackApprovalFormContext(
                commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                requestHeaders = this.slackCommandData.rawHeader,
                slackApiRequester = this.slackApiRequester,
                events = events
            )
            CommandSet.UNKNOWN -> DetailErrorAlertContext(
                slackCommandData = this.slackCommandData, errorMessage = "Command \"$command\" not found",
                targetClassName = this::class.simpleName ?: "SlackAppMentionContext", details = null,
                slackApiRequester = this.slackApiRequester, idempotencyKey = this.idempotencyKey,
                events = events
            )
        }
    }

    private fun verifyCommandQueue(commandQueue: Queue<String>) {
        if (commandQueue.isEmpty()) throw IllegalArgumentException("Command Queue is empty")
    }

}