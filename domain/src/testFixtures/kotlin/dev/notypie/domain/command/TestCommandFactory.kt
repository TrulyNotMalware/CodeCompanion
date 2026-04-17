package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

/**
 * Test-only Command subclass for exercising CommandExecutor / integration tests.
 * Produces a single optional intent and always succeeds.
 */
class TestCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    private val intentToProduce: CommandIntent? = null,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> =
        TestContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
            intentToProduce = intentToProduce,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}

internal class TestContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    private val intentToProduce: CommandIntent?,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        requestHeaders = SlackRequestHeaders(),
        intents = intents,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        if (intentToProduce != null) {
            addIntent(intent = intentToProduce)
        }
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
