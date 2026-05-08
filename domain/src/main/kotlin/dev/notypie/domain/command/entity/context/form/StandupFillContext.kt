package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

internal class StandupFillContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_FILL

    /**
     * "Fill in standup" button carries `<idempotencyKey>,STANDUP_FILL,<sessionUid>,<routineUid>`.
     * routingExtras[0] = sessionUid, routingExtras[1] = routineUid. Missing or malformed
     * extras fall through to a no-op success response.
     */
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val sessionUid =
            interactionPayload.routingExtras
                .getOrNull(0)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        val routineUid =
            interactionPayload.routingExtras
                .getOrNull(1)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        addIntent(
            CommandIntent.OpenStandupModal(
                triggerId = interactionPayload.triggerId,
                sessionUid = sessionUid,
                routineUid = routineUid,
                requesterId = interactionPayload.user.id,
                noticeChannel = interactionPayload.channel.id,
                noticeMessageTs = interactionPayload.container.messageTs.orEmpty(),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
