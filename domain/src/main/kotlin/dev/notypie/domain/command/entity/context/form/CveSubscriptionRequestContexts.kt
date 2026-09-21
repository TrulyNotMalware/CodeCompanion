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
