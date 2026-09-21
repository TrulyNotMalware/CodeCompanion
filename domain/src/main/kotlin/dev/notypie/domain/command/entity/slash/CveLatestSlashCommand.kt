package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestCveLatestContext
import dev.notypie.domain.command.inbound.InboundCommand
import java.util.UUID

class CveLatestSlashCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val topicKey: String?,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> =
        RequestCveLatestContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            topicKey = topicKey,
            subCommand = subCommand,
            intents = intents,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}
