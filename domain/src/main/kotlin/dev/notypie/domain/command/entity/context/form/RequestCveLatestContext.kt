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

internal class RequestCveLatestContext(
    commandBasicInfo: CommandBasicInfo,
    private val topicKey: String?,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_LATEST

    override fun runCommand(): CommandOutput {
        addIntent(CommandIntent.CveLatest(userId = commandBasicInfo.publisherId, topicKey = topicKey))
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
