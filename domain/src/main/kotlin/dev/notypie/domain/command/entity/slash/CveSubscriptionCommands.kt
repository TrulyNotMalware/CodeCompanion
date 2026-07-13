package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestCveSubscribeContext
import dev.notypie.domain.command.entity.context.form.RequestCveSubscriptionsContext
import dev.notypie.domain.command.entity.context.form.RequestCveUnsubscribeContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.outbound.TopicOption
import java.util.UUID

/**
 * `/subscribe` slash command. Mirrors [SetupStandupCommand]: a slash invocation whose sole job is to
 * open a modal synchronously so the Slack `trigger_id` is consumed before it expires. [topics] are the
 * active topics the application service resolved before building this command (the domain never queries
 * persistence).
 */
class CveSubscribeSlashCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val topics: List<TopicOption>,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> {
        val slashPayload = commandData.payload as SlashInvocation
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

/**
 * `/unsubscribe` slash command. Opens a modal listing only [topics] — the user's current
 * subscriptions, resolved by the application service before this command is built.
 */
class CveUnsubscribeSlashCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val topics: List<TopicOption>,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> {
        val slashPayload = commandData.payload as SlashInvocation
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

/**
 * `/subscriptions` slash command. No modal: emits the LIST intent directly so the listener DMs the
 * user their current subscriptions.
 */
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
