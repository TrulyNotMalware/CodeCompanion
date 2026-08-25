package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Context for the admin-only CVE operations mentions (`cve topics`, `cve topic activate|deactivate`,
 * `cve retry ...`). Mirrors [RoleManagementContext]: the parser validates the mention shape and hands
 * over a ready [CommandIntent]; the application listener (which owns the CVE repositories and the
 * feature gate) executes it and replies.
 */
internal class CveOpsContext(
    private val intent: CommandIntent,
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        subCommand = SubCommand.empty(),
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        addIntent(intent)
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
