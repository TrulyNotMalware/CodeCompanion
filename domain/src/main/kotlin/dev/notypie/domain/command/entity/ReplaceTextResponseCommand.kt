package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReplaceMessageContext
import dev.notypie.domain.command.inbound.InboundCommand
import java.util.UUID

class ReplaceTextResponseCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val markdownMessage: String,
    private val replyHandle: String,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<NoSubCommands> =
        ReplaceMessageContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            markdownMessage = markdownMessage,
            replyHandle = replyHandle,
            subCommand = subCommand,
            intents = intents,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}
