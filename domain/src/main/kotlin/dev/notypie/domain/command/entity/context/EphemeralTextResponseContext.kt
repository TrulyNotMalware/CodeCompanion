package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage

internal class EphemeralTextResponseContext(
    commandBasicInfo: CommandBasicInfo,
    isOk: Boolean = true,
    private val textMessage: String,
    intents: IntentQueue,
) : ResponseContext(
        commandBasicInfo = commandBasicInfo,
        isOk = isOk,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        addOutbound(
            OutboundMessage.Ephemeral(
                target = ConversationTarget(id = commandBasicInfo.channel),
                recipient = null,
                content = MessageContent.Text(headline = null, markdown = textMessage),
            ),
        )
        return if (isOk) {
            CommandOutput.success(
                basicInfo = commandBasicInfo,
                commandType = commandType,
                commandDetailType = commandDetailType,
            )
        } else {
            CommandOutput.fail(
                basicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
                reason = textMessage,
                commandType = commandType,
            )
        }
    }
}
