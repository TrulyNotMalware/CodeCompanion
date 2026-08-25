package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.TopicOption

/**
 * Handles `/subscribe` by emitting [OutboundMessage.OpenModal]; the stager lifts it to a synchronous
 * `views.open` so [triggerHandle] is consumed within Slack's 3-second window. [topics] are the active
 * topics the application service already resolved (the domain never touches persistence).
 */
internal class RequestCveSubscribeContext(
    commandBasicInfo: CommandBasicInfo,
    private val triggerHandle: String,
    private val topics: List<TopicOption>,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_SUBSCRIBE_REQUEST

    override fun runCommand(): CommandOutput {
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = triggerHandle),
                form = ModalForm.CveSubscribe(topics = topics),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}

/**
 * Handles `/unsubscribe` by opening a modal listing only [topics] — the user's current subscriptions,
 * resolved by the application service.
 */
internal class RequestCveUnsubscribeContext(
    commandBasicInfo: CommandBasicInfo,
    private val triggerHandle: String,
    private val topics: List<TopicOption>,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_UNSUBSCRIBE_REQUEST

    override fun runCommand(): CommandOutput {
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = triggerHandle),
                form = ModalForm.CveUnsubscribe(topics = topics),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}

/**
 * Handles `/subscriptions` — no modal. Emits [CommandIntent.CveListSubscriptions] directly; the
 * resolver lifts it to the same request event the modal flows use (action = LIST), and the listener
 * DMs the user their current subscription list.
 */
internal class RequestCveSubscriptionsContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_SUBSCRIPTIONS_LIST

    override fun runCommand(): CommandOutput {
        addIntent(CommandIntent.CveListSubscriptions(userId = commandBasicInfo.publisherId))
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
