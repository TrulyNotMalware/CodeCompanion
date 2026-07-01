package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle

internal class ReplaceMessageContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    private val responseUrl: String,
    private val markdownMessage: String,
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REPLACE_TEXT

    override fun runCommand(): CommandOutput = replaceText()

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput = replaceText()

    private fun replaceText(): CommandOutput {
        addOutbound(
            OutboundMessage.ReplaceMessage(
                handle = ResponseReplaceHandle(raw = responseUrl),
                content = MessageContent.Text(headline = null, markdown = markdownMessage),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
