package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.parsers.AppMentionCommandParser
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.entity.parsers.ContextParser
import dev.notypie.domain.command.entity.parsers.InteractionCommandParser
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

class CompositeCommand(
    val appName: String,
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
    eventPublisher: EventPublisher,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester,
    eventPublisher = eventPublisher
) {
    companion object{
        const val BASE_URL: String = "https://slack.com/api/"
    }

    private val commandParser: ContextParser = this.buildParser(this.commandData)

    override fun parseContext(): CommandContext =
        this.commandParser.parseContext(events = this.events, idempotencyKey = this.idempotencyKey)

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
                slackApiRequester = this.slackApiRequester, commandId = this.commandId,
                idempotencyKey = this.idempotencyKey, events = events
            )
            else -> TODO()
        }
    }

    private fun handleInteractions(commandData: SlackCommandData): ContextParser {
        val interactionPayload = commandData.body as InteractionPayload
        val type = interactionPayload.type
        return InteractionCommandParser(slackCommandData = commandData,
            baseUrl = BASE_URL, commandId = this.commandId,
            idempotencyKey = this.idempotencyKey, slackApiRequester = this.slackApiRequester,
            events = events
        )
//        return when(type){
//            CommandDetailType.APPROVAL_FORM
//        }
    }

    private fun handleNotSupportedCommand(): SlackTextResponseContext = SlackTextResponseContext(
        requestHeaders = this.commandData.rawHeader,
        slackApiRequester = this.slackApiRequester, text = "Command Not supported.",
        commandBasicInfo = this.commandData.extractBasicInfo(
            idempotencyKey = this.idempotencyKey,
        ),
        events = this.events
    )
}