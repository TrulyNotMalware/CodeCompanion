package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.parsers.AppMentionCommandParser
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.entity.parsers.ContextParser
import dev.notypie.domain.command.entity.parsers.InteractionCommandParser
import java.util.*

class CompositeCommand(
    val appName: String,
    idempotencyKey: String,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester
) {
    companion object{
        const val BASE_URL: String = "https://slack.com/api/"
    }

    val commandId: UUID = this.generateIdValue()
    private val commandParser: ContextParser
    private val commandContext: CommandContext
    init {
        this.commandParser = this.buildParser(this.commandData)
        this.commandContext = this.commandParser.parseContext(idempotencyKey = this.idempotencyKey)
    }

    override fun handleEvent(): SlackApiResponse =
        if( commandData.slackCommandType == SlackCommandType.INTERACTION_RESPONSE)
            this.commandContext.handleInteraction(this.commandData.body as InteractionPayload)
        else this.commandContext.runCommand()

    private fun generateIdValue(): UUID = UUID.randomUUID()

    private fun buildParser(commandData: SlackCommandData): ContextParser {
        return when(commandData.slackCommandType){
            //Removal challenge requests.
            SlackCommandType.EVENT_CALLBACK -> this.handleEventCallBackContext(commandData = commandData)
            SlackCommandType.INTERACTION_RESPONSE -> this.handleInteractions(commandData = commandData)
            else -> TODO()
        }
    }

    private fun handleEventCallBackContext( commandData: SlackCommandData ): ContextParser {
        val eventCallBack = commandData.body as SlackEventCallBackRequest
        val type = SlackCommandType.valueOf(eventCallBack.event.type.uppercase())
        return when(type){
            SlackCommandType.APP_MENTION -> AppMentionCommandParser(
                slackCommandData = commandData, baseUrl = BASE_URL,
                slackApiRequester = this.slackApiRequester, commandId = this.commandId , idempotencyKey = this.idempotencyKey)
            else -> TODO()
        }
    }

    private fun handleInteractions(commandData: SlackCommandData): ContextParser {
        val interactionPayload = commandData.body as InteractionPayload
        val type = interactionPayload.type
        return InteractionCommandParser(slackCommandData = commandData,
            baseUrl = BASE_URL, commandId = this.commandId,
            idempotencyKey = this.idempotencyKey, slackApiRequester = this.slackApiRequester)
//        return when(type){
//            CommandDetailType.APPROVAL_FORM
//        }
    }

    private fun handleNotSupportedCommand(): SlackTextResponseContext = SlackTextResponseContext(
        requestHeaders = this.commandData.rawHeader,
        slackApiRequester = this.slackApiRequester, text = "Command Not supported.",
        commandBasicInfo = this.commandData.extractBasicInfo(this.idempotencyKey)
    )
}