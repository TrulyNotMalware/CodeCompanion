package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.parsers.AppMentionContextParser
import dev.notypie.domain.command.entity.parsers.ContextParser
import dev.notypie.domain.command.entity.parsers.InteractionContextParser
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.UnSupportedCommandException
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.common.error.exceptionDetails
import java.util.UUID

class InteractionCommand(
    val appName: String,
    idempotencyKey: UUID,
    commandData: InboundCommand,
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
        val interaction =
            commandData.payload as? InboundInteraction
                ?: return NoSubCommands()

        return when (interaction.detailType) {
            CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
            CommandDetailType.REQUEST_MEETING_FORM,
            -> MeetingSubCommandDefinition.NONE

            else -> NoSubCommands()
        }
    }

    private fun buildParser(commandData: InboundCommand): ContextParser =
        when (val payload = commandData.payload) {
            is MentionInvocation ->
                AppMentionContextParser(
                    commandData = commandData,
                    mention = payload,
                    baseUrl = BASE_URL,
                    commandId = commandId,
                    idempotencyKey = idempotencyKey,
                    intents = intents,
                )

            is InboundInteraction ->
                InteractionContextParser(
                    commandData = commandData,
                    interaction = payload,
                    baseUrl = BASE_URL,
                    commandId = commandId,
                    idempotencyKey = idempotencyKey,
                    intents = intents,
                )

            else ->
                throw UnSupportedCommandException(
                    commandType = commandData.kind.toString(),
                    errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
                    details =
                        exceptionDetails {
                            "kind" value commandData.kind.toString() because
                                "Only MENTION and INTERACTION payloads are supported by InteractionCommand"
                        },
                )
        }
}
