package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.entity.parsers.AppMentionContextParser
import dev.notypie.domain.command.entity.parsers.ContextParser
import dev.notypie.domain.command.entity.parsers.InteractionContextParser
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.UnSupportedCommandException
import dev.notypie.domain.common.error.exceptionDetails
import java.util.UUID

class InteractionCommand(
    val appName: String,
    idempotencyKey: UUID,
    commandData: SlackCommandData,
) : Command<SubCommandDefinition>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    companion object {
        const val BASE_URL: String = "https://slack.com/api/"
    }

    // Lazy so that UnSupportedCommandException thrown here is captured by Command.handleEvent()
    // rather than breaking Command construction.
    private val commandParser: ContextParser by lazy { buildParser(commandData) }

    override fun parseContext(subCommand: SubCommand<SubCommandDefinition>): CommandContext<out SubCommandDefinition> =
        commandParser.parseContext(idempotencyKey = idempotencyKey)

    override fun findSubCommandDefinition(): SubCommandDefinition {
        val payload =
            commandData.body as? InteractionPayload
                ?: return NoSubCommands()

        return when (payload.type) {
            CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
            CommandDetailType.REQUEST_MEETING_FORM,
            -> MeetingSubCommandDefinition.NONE

            else -> NoSubCommands()
        }
    }

    private fun buildParser(commandData: SlackCommandData): ContextParser =
        when (commandData.slackCommandType) {
            SlackCommandType.EVENT_CALLBACK -> handleEventCallBackContext(commandData = commandData)

            SlackCommandType.INTERACTION_RESPONSE -> handleInteractions(commandData = commandData)

            else -> throw UnSupportedCommandException(
                commandType = commandData.slackCommandType.toString(),
                errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
                details =
                    exceptionDetails {
                        "slackCommandType" value commandData.slackCommandType.toString() because
                            "Only EVENT_CALLBACK and INTERACTION_RESPONSE are supported by InteractionCommand"
                    },
            )
        }

    private fun handleEventCallBackContext(commandData: SlackCommandData): ContextParser {
        val eventCallBack = commandData.body as SlackEventCallBackRequest
        val rawType = eventCallBack.event.type
        val type =
            runCatching { SlackCommandType.valueOf(rawType.uppercase()) }
                .getOrElse { cause ->
                    throw UnSupportedCommandException(
                        commandType = rawType,
                        errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
                        details =
                            exceptionDetails {
                                "eventType" value rawType because
                                    "Unknown Slack event.type value: ${cause.message}"
                            },
                    )
                }
        return when (type) {
            SlackCommandType.APP_MENTION -> {
                AppMentionContextParser(
                    slackCommandData = commandData,
                    baseUrl = BASE_URL,
                    commandId = commandId,
                    idempotencyKey = idempotencyKey,
                    intents = intents,
                )
            }

            else -> {
                throw UnSupportedCommandException(
                    commandType = type.toString(),
                    errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
                    details =
                        exceptionDetails {
                            "eventType" value type.toString() because
                                "Only APP_MENTION event callbacks are supported"
                        },
                )
            }
        }
    }

    private fun handleInteractions(commandData: SlackCommandData): ContextParser {
        val interactionPayload = commandData.body as InteractionPayload
        val type = interactionPayload.type
        return InteractionContextParser(
            slackCommandData = commandData,
            baseUrl = BASE_URL,
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            intents = intents,
        )
    }

    private fun handleNotSupportedCommand(): SlackTextResponseContext =
        SlackTextResponseContext(
            requestHeaders = commandData.rawHeader,
            text = "Command Not supported.",
            commandBasicInfo =
                commandData.extractBasicInfo(
                    idempotencyKey = idempotencyKey,
                ),
            intents = intents,
        )
}
