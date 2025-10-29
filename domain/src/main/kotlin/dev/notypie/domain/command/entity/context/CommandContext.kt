package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import kotlin.reflect.KClass

internal abstract class CommandContext(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val slackEventBuilder: SlackEventBuilder,
    val subCommand: SubCommand = SubCommand.empty(),
    val events: EventQueue<CommandEvent<EventPayload>>,
    // If you do not override the type, only "NoSubCommands" is allowed.
    requiredSubCommandType: KClass<out SubCommandDefinition> = NoSubCommands::class,
) {
    val commandType: CommandType by lazy { parseCommandType() }
    val commandDetailType: CommandDetailType by lazy { parseCommandDetailType() }

    init {
        val actual = subCommand.subCommandDefinition::class

        if (actual != NoSubCommands::class && actual != requiredSubCommandType) {
            throw SubCommandParseException(
                commandName = this::class.java.simpleName,
                subCommandName = subCommand.subCommandDefinition.subCommandIdentifier,
                errorCode = CommandErrorCode.UNKNOWN_SUBCOMMAND_TYPE,
                details =
                    exceptionDetails {
                        "expected" value requiredSubCommandType.simpleName.orEmpty() because "Expected subcommand type"
                        "actual" value actual.simpleName.orEmpty() because "Actual subcommand type"
                    },
            )
        }
    }

    protected abstract fun parseCommandType(): CommandType

    protected abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): CommandOutput = CommandOutput.empty()

    protected fun createErrorResponse(errMessage: String): CommandOutput =
        EphemeralTextResponseContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            textMessage = errMessage,
            events = events,
            isOk = false,
        ).runCommand()

    protected fun createErrorResponse(errMessage: String, results: CommandOutput): CommandOutput {
        EphemeralTextResponseContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            textMessage = errMessage,
            events = events,
            isOk = false,
        ).runCommand()
        return results
    }

    protected fun addNewEvent(commandEvent: CommandEvent<EventPayload>) = events.offer(event = commandEvent)
}
