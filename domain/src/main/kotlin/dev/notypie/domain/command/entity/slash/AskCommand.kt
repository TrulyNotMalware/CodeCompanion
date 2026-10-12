package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.AskSlashContext
import dev.notypie.domain.command.inbound.InboundCommand
import java.util.UUID

internal const val ASK_COMMAND_IDENTIFIER: String = "ask"

internal const val ASK_USAGE: String = "/$ASK_COMMAND_IDENTIFIER <question>"

class AskCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val actorRole: UserRole,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override val slashCommandName: String = "/$ASK_COMMAND_IDENTIFIER"

    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> =
        AskSlashContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            prompt = commandData.subCommands.joinToString(separator = " ").trim(),
            requesterName = commandData.actorName,
            channelName = commandData.channelName,
            actorRole = actorRole,
            subCommand = subCommand,
            intents = intents,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}
