package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.CalendarSlashContext
import dev.notypie.domain.command.inbound.InboundCommand
import java.util.UUID

internal const val CALENDAR_COMMAND_IDENTIFIER: String = "calendar"

internal const val CALENDAR_USAGE: String = "/$CALENDAR_COMMAND_IDENTIFIER connect | disconnect | status"

class CalendarCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override val slashCommandName: String = "/$CALENDAR_COMMAND_IDENTIFIER"

    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> =
        CalendarSlashContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            arguments = commandData.subCommands,
            subCommand = subCommand,
            intents = intents,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}
