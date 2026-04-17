package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

internal class DetailErrorAlertContext(
    slackCommandData: SlackCommandData,
    private val targetClassName: String,
    private val errorMessage: String,
    private val details: String?,
    idempotencyKey: UUID,
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        requestHeaders = slackCommandData.rawHeader,
        commandBasicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey),
        intents = intents,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        addIntent(
            CommandIntent.ErrorDetail(
                errorClassName = targetClassName,
                errorMessage = errorMessage,
                details = details,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
