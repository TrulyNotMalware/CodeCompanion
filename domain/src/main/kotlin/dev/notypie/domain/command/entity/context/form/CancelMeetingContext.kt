package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

internal class CancelMeetingContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CANCEL_MEETING

    /**
     * Cancel button on `/meetup list` carries `<listIdempotencyKey>,CANCEL_MEETING,<meetingUid>`.
     * The parser surfaces the meetingUid as the first routing extra. Missing/malformed extras
     * fall through to a no-op response — the WHERE clause in the repository still defends
     * against bogus uids, so we don't need to throw here.
     */
    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val meetingUid =
            interaction.routingExtras
                .firstOrNull()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        addIntent(
            CommandIntent.CancelMeeting(
                meetingUid = meetingUid,
                requesterId = interaction.actor.id,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
