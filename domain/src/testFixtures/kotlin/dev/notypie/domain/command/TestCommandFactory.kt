package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

/**
 * Test-only Command subclass for exercising CommandExecutor / integration tests.
 * Produces a single optional intent and always succeeds.
 */
class TestCommand(
    idempotencyKey: UUID,
    commandData: InboundCommand,
    private val intentToProduce: CommandIntent? = null,
    private val rawEffectToProduce: CommandEffect? = null,
) : Command<NoSubCommands>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(subCommand: SubCommand<NoSubCommands>): CommandContext<out NoSubCommands> =
        TestContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
            intentToProduce = intentToProduce,
            rawEffectToProduce = rawEffectToProduce,
        )

    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
}

internal class TestContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    private val intentToProduce: CommandIntent?,
    private val rawEffectToProduce: CommandEffect? = null,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        if (intentToProduce != null) {
            addIntent(intent = intentToProduce)
        }
        if (rawEffectToProduce != null) {
            intents.offer(effect = rawEffectToProduce)
        }
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
