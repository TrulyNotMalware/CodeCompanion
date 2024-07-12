package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandSet
import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.mention.Element
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.SlackRequestHandler
import java.util.*

class SlackAppMentionContext(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,

    slackApiRequester: SlackApiRequester
): CommandContext(
    channel = slackCommandData.channel,
    appToken = slackCommandData.appToken,
    requestHeaders = slackCommandData.rawHeader,
    slackApiRequester = slackApiRequester
) {
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
        this.parsedContext = parseContextFromData()
    }

    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun runCommand() = this.parsedContext.runCommand()

    private fun parseContextFromData(): CommandContext = this.slackAppMentionRequestData.event.blocks
        .find { blocks -> blocks.elements.isNotEmpty() && blocks.type == BLOCK_TYPE_RICH_TEXT }
        ?.elements?.find { element -> element.type == ELEMENT_TYPE_TEXT_SECTION }
        ?.let { this.extractUserAndCommand(elements = it.elements) }
        ?.let { this.buildContext(it.first, it.second) }
        ?: this.handleNotSupportedCommand()



    private fun handleNotSupportedCommand(): SlackTextResponseContext = SlackTextResponseContext(
        channel = this.channel, appToken = this.appToken, requestHeaders = this.requestHeaders,
        slackApiRequester = this.slackApiRequester, text = "Command Not supported."
    )

    private fun extractUserAndCommand(elements : List<Element>?):
            Pair<Queue<String>, Queue<String>> {
        val userQueue: Queue<String> = LinkedList()
        val commandQueue: Queue<String> = LinkedList()
        elements
            ?.forEach { element ->
                when (element.type) {
                    ELEMENT_TYPE_USER -> if (element.userId != this.botId) userQueue.offer(element.userId)
                    ELEMENT_TYPE_TEXT -> element.text?.split(COMMAND_DELIMITER)?.forEach { if(it.isNotBlank()) commandQueue.offer(it) }
                }
            }
        this.verifyCommandQueue(commandQueue = commandQueue)
        return Pair(userQueue, commandQueue)
    }

    private fun buildContext(userQueue: Queue<String>, commandQueue: Queue<String>): CommandContext{
        val command: String = commandQueue.poll().replace(" ", "")
        return when(CommandSet.parseCommand(command)){
            CommandSet.NOTICE ->
                SlackNoticeContext(
                    users = userQueue, commands = commandQueue,
                    channel = this.channel, appToken = this.appToken, requestHeaders = this.requestHeaders,
                    slackApiRequester = this.slackApiRequester)
            CommandSet.UNKNOWN -> SlackErrorAlertContext(
                slackCommandData = this.slackCommandData, errorMessage = "Command \"$command\" not found",
                targetClassName = this::class.simpleName ?: "SlackAppMentionContext", details = null,
                slackApiRequester = this.slackApiRequester)
        }
    }

    private fun verifyCommandQueue(commandQueue: Queue<String>) {
        if (commandQueue.isEmpty()) throw IllegalArgumentException("Command Queue is empty")
    }

}