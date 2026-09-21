package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.authorization.UserRole
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
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.domain.common.error.exceptionDetails
import java.util.UUID

class InteractionCommand(
    val appName: String,
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val actorRole: UserRole,
    private val parseObserver: SubmissionParseObserver = SubmissionParseObserver.NONE,
) : Command<SubCommandDefinition>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    private data class Route(
        val parser: ContextParser,
        val subCommandDefinition: SubCommandDefinition,
    )

    // Lazy so UnSupportedCommandException is caught by handleEvent(), not thrown during construction.
    private val route: Route by lazy { resolveRoute(commandData = commandData) }

    override fun parseContext(subCommand: SubCommand<SubCommandDefinition>): CommandContext<out SubCommandDefinition> =
        route.parser.parseContext(idempotencyKey = idempotencyKey)

    override fun findSubCommandDefinition(): SubCommandDefinition = route.subCommandDefinition

    private fun resolveRoute(commandData: InboundCommand): Route =
        when (val payload = commandData.payload) {
            is MentionInvocation ->
                Route(
                    parser =
                        AppMentionContextParser(
                            commandData = commandData,
                            mention = payload,
                            idempotencyKey = idempotencyKey,
                            intents = intents,
                            actorRole = actorRole,
                        ),
                    subCommandDefinition = NoSubCommands(),
                )

            is InboundInteraction ->
                Route(
                    parser =
                        InteractionContextParser(
                            commandData = commandData,
                            interaction = payload,
                            idempotencyKey = idempotencyKey,
                            intents = intents,
                            observer = parseObserver,
                        ),
                    subCommandDefinition =
                        when (payload.detailType) {
                            CommandDetailType.MEETING_APPROVAL_REQUEST,
                            CommandDetailType.MEETING_CREATE_REQUEST,
                            -> MeetingSubCommandDefinition.NONE

                            else -> NoSubCommands()
                        },
                )

            is SlashInvocation ->
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
