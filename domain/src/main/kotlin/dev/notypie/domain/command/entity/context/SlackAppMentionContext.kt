package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandSet
import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.mention.Element
import dev.notypie.domain.command.dto.mention.SlackAppMentionRequest
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.domain.command.SlackRequestHandler
import java.util.LinkedList
import java.util.Queue

class SlackAppMentionContext(
    val slackCommandData: SlackCommandData,
    val baseUrl: String,
    responseBuilder: SlackRequestBuilder,
    requestHandler: SlackRequestHandler
): CommandContext(
    channel = slackCommandData.channel,
    appToken = slackCommandData.appToken,
    requestHeaders = slackCommandData.rawHeader,
    responseBuilder = responseBuilder,
    requestHandler = requestHandler
) {
    companion object{
        const val BLOCK_TYPE_RICH_TEXT = "rich_text"
        const val ELEMENT_TYPE_TEXT_SECTION = "rich_text_section"

        const val ELEMENT_TYPE_USER = "user"
        const val ELEMENT_TYPE_TEXT = "text"

        const val COMMAND_DELIMITER = " "
    }

    private val slackAppMentionRequestData: SlackAppMentionRequest = slackCommandData.body as SlackAppMentionRequest
    private val botId: String = slackAppMentionRequestData.authorizations
        .find { it.isBot }?.userId ?: ""
    private val parsedContext: CommandContext = this.parseContextFromData()

    override fun parseCommandType(): CommandType = this.parsedContext.commandType
    override fun runCommand() = this.parsedContext.runCommand()

    private fun parseContextFromData(): CommandContext{
        return this.slackAppMentionRequestData.event.blocks
            .find { blocks -> blocks.elements.isNotEmpty() && blocks.type == BLOCK_TYPE_RICH_TEXT }
            ?.elements?.find { element -> element.type == ELEMENT_TYPE_TEXT_SECTION }
            ?.let { this.extractUserAndCommand(elements = it.elements) }
            ?.let { this.buildContext(it.first, it.second) }
            ?: this.defaultCommandContext()
    }

    private fun defaultCommandContext(): SlackTextResponseContext = SlackTextResponseContext(
        channel = this.channel, appToken = this.appToken, requestHeaders = this.requestHeaders,
        responseBuilder = this.responseBuilder, requestHandler = requestHandler
    )

    private fun extractUserAndCommand(elements : List<Element>):
            Pair<Queue<String>, Queue<String>> {
        val userQueue: Queue<String> = LinkedList()
        val commandQueue: Queue<String> = LinkedList()

        elements
            .forEach { element ->
                when (element.type) {
                    ELEMENT_TYPE_USER -> if (element.userId != this.botId) userQueue.offer(element.userId)
                    ELEMENT_TYPE_TEXT -> element.text.split(COMMAND_DELIMITER).forEach { commandQueue.offer(it) }
                }
            }
        this.verifyCommandQueue(commandQueue = commandQueue)
        return Pair(userQueue, commandQueue)
    }

    private fun buildContext(userQueue: Queue<String>, commandQueue: Queue<String>): CommandContext{
        val command: String = commandQueue.poll().replace(" ", "")
        return when(CommandSet.valueOf(command.uppercase())){
            CommandSet.NOTICE ->
                SlackNoticeContext(
                    users = userQueue, commands = commandQueue,
                    channel = this.channel, appToken = this.appToken, requestHeaders = this.requestHeaders,
                    responseBuilder = this.responseBuilder, requestHandler = requestHandler)
        }
    }

    private fun verifyCommandQueue(commandQueue: Queue<String>) {
        if (commandQueue.isEmpty()) throw IllegalArgumentException("Command Queue is empty")
    }

}