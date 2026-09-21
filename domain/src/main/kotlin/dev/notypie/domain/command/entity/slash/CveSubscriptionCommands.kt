package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestCveSubscribeContext
import dev.notypie.domain.command.entity.context.form.RequestCveSubscriptionsContext
import dev.notypie.domain.command.entity.context.form.RequestCveUnsubscribeContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.outbound.TopicOption
import java.util.UUID

class CveSubscribeSlashCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val topics: List<TopicOption>,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> {
        val slashPayload = commandData.slashInvocation(commandName = "CveSubscribeSlashCommand")
        return RequestCveSubscribeContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            triggerHandle = slashPayload.trigger.raw,
            topics = topics,
            subCommand = subCommand,
            intents = intents,
        )
    }

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}

class CveUnsubscribeSlashCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val topics: List<TopicOption>,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> {
        val slashPayload = commandData.slashInvocation(commandName = "CveUnsubscribeSlashCommand")
        return RequestCveUnsubscribeContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            triggerHandle = slashPayload.trigger.raw,
            topics = topics,
            subCommand = subCommand,
            intents = intents,
        )
    }

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}

class CveSubscriptionsSlashCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> =
        RequestCveSubscriptionsContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            subCommand = subCommand,
            intents = intents,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}
