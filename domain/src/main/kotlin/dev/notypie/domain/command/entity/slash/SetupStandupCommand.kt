package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestStandupSetupContext
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.findSubCommandByIdentifier
import dev.notypie.domain.common.error.exceptionDetails
import java.util.UUID

/**
 * `/standup setup` slash command. Mirrors [RequestMeetingCommand]: a slash invocation whose
 * sole job is to open a modal synchronously so the Slack `trigger_id` is consumed before it
 * expires. The live trigger_id and the invoking channel are read off the
 * [SlashCommandRequestBody] (carried on [SlackCommandData.body]) and threaded into
 * [RequestStandupSetupContext], which emits the modal-opening intent.
 */
class SetupStandupCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
) : Command<StandupSubCommandDefinition>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(
        subCommand: SubCommand<StandupSubCommandDefinition>,
    ): CommandContext<StandupSubCommandDefinition> {
        val slashBody = commandData.body as SlashCommandRequestBody
        return RequestStandupSetupContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            triggerId = slashBody.triggerId,
            subCommand = subCommand,
            intents = intents,
        )
    }

    override fun findSubCommandDefinition(): StandupSubCommandDefinition {
        val identifier =
            commandData.subCommands.firstOrNull()
                ?: return StandupSubCommandDefinition.NONE
        return findSubCommandByIdentifier<StandupSubCommandDefinition>(identifier = identifier)
            ?: throw SubCommandParseException(
                commandName = this::class.java.simpleName,
                subCommandName = identifier,
                errorCode = CommandErrorCode.SUBCOMMAND_NOT_FOUND,
                details =
                    exceptionDetails {
                        "subCommandIdentifier" value identifier because "subcommand $identifier not found"
                    },
            )
    }
}

internal const val STANDUP_COMMAND_IDENTIFIER: String = "standup"

enum class StandupSubCommandDefinition(
    override val subCommandIdentifier: String,
    override val usage: String,
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
) : SubCommandDefinition {
    NONE(
        subCommandIdentifier = "",
        usage = "",
    ),
    SETUP(
        subCommandIdentifier = "setup",
        usage = "/$STANDUP_COMMAND_IDENTIFIER setup",
    ),
}
