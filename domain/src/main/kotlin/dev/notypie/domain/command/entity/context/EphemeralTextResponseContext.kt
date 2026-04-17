package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

internal class EphemeralTextResponseContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    isOk: Boolean = true,
    private val textMessage: String,
    intents: IntentQueue,
) : ResponseContext(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        isOk = isOk,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        addIntent(CommandIntent.EphemeralResponse(message = textMessage))
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
