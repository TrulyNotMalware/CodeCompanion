package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.entity.parsers.AppMentionCommandParser
import dev.notypie.domain.command.entity.parsers.ContextParser
import dev.notypie.domain.command.entity.parsers.InteractionCotextParser
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

class InteractionCommand(
    val appName: String,
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackEventBuilder: SlackEventBuilder,
    eventPublisher: EventPublisher,
) : Command(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
        slackEventBuilder = slackEventBuilder,
        eventPublisher = eventPublisher,
    ) {
    companion object {
        const val BASE_URL: String = "https://slack.com/api/"
    }

    private val commandParser: ContextParser = buildParser(commandData)

    override fun parseContext(subCommand: SubCommand): CommandContext =
        commandParser.parseContext(idempotencyKey = idempotencyKey)

    override fun findSubCommandDefinition(): SubCommandDefinition = NoSubCommands()

    private fun buildParser(commandData: SlackCommandData): ContextParser =
        when (commandData.slackCommandType) {
            // Removal challenge requests.
            SlackCommandType.EVENT_CALLBACK -> handleEventCallBackContext(commandData = commandData)
            SlackCommandType.INTERACTION_RESPONSE -> handleInteractions(commandData = commandData)
            else -> TODO()
        }

    private fun handleEventCallBackContext(commandData: SlackCommandData): ContextParser {
        val eventCallBack = commandData.body as SlackEventCallBackRequest
        val type = SlackCommandType.valueOf(eventCallBack.event.type.uppercase())
        return when (type) {
            SlackCommandType.APP_MENTION ->
                AppMentionCommandParser(
                    slackCommandData = commandData,
                    baseUrl = BASE_URL,
                    slackEventBuilder = slackEventBuilder,
                    commandId = commandId,
                    idempotencyKey = idempotencyKey,
                    events = events,
                )
            else -> TODO()
        }
    }

    private fun handleInteractions(commandData: SlackCommandData): ContextParser {
        val interactionPayload = commandData.body as InteractionPayload
        val type = interactionPayload.type
        return InteractionCotextParser(
            slackCommandData = commandData,
            baseUrl = BASE_URL,
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            slackEventBuilder = slackEventBuilder,
            events = events,
        )
//        return when(type){
//            CommandDetailType.APPROVAL_FORM
//        }
    }

    private fun handleNotSupportedCommand(): SlackTextResponseContext =
        SlackTextResponseContext(
            requestHeaders = commandData.rawHeader,
            slackEventBuilder = slackEventBuilder,
            text = "Command Not supported.",
            commandBasicInfo =
                commandData.extractBasicInfo(
                    idempotencyKey = idempotencyKey,
                ),
            events = events,
        )
}
