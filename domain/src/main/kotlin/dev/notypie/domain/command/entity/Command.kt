package dev.notypie.domain.command.entity

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

abstract class Command(
    val idempotencyKey: UUID,
    val commandData: SlackCommandData,
    internal val slackEventBuilder: SlackEventBuilder,
    internal val eventPublisher: EventPublisher,
) {
    internal val events: EventQueue<CommandEvent<EventPayload>> = DefaultEventQueue()

    val commandId: UUID = UUID.randomUUID()

    internal abstract fun parseContext(subCommand: SubCommand): CommandContext

    internal open fun findSubCommandDefinition(): SubCommandDefinition = NoSubCommands()

    fun handleEvent() =
        runCatching { executeCommand() }
            .onSuccess { publishEvents() }
            .getOrElse { exception ->
                CommandOutput.fail(
                    slackCommandData = commandData,
                    idempotencyKey = idempotencyKey,
                    commandDetailType = CommandDetailType.ERROR_RESPONSE,
                    reason = exception.toString(),
                )
            }

    private fun publishEvents() = eventPublisher.publishEvent(events = events)

    internal fun executeCommand(): CommandOutput {
        val subCommand = createSubCommand()
        val context = parseContext(subCommand = subCommand)
        return if (commandData.slackCommandType == SlackCommandType.INTERACTION_RESPONSE) {
            context.handleInteraction(commandData.body as InteractionPayload)
        } else {
            context.runCommand()
        }
    }

    private fun createSubCommand(): SubCommand {
        val options = commandData.subCommands.drop(1)
        val subCommand =
            SubCommand(
                subCommandDefinition = findSubCommandDefinition(),
                options = options,
            )
        return if (subCommand.isValid()) {
            subCommand
        } else {
            throw SubCommandParseException(
                commandName = this::class.java.simpleName,
                subCommandName = subCommand.subCommandDefinition.subCommandIdentifier,
                errorCode = CommandErrorCode.SUBCOMMAND_NOT_VALID,
                details =
                    exceptionDetails {
                        "subcommand options" value options.joinToString { "," } because "sub command validation failed"
                    },
            )
        }
    }
}
