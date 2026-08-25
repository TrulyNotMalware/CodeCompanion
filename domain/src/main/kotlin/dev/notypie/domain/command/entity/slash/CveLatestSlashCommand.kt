package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestCveLatestContext
import dev.notypie.domain.command.inbound.InboundCommand
import java.util.UUID

/**
 * `/latest [topic-key]` slash command. No modal: emits the CveLatest intent directly so the listener
 * DMs the caller the most recent DONE summaries. [topicKey] is the resolved single-topic scope (null
 * for the no-argument form), extracted by the application service before this command is built.
 */
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
