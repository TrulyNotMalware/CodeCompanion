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

/**
 * "Add participant" button on `/meetup list` carries `<listIdempotencyKey>,ADD_PARTICIPANT,<meetingUid>`.
 * The parser surfaces the meetingUid as the first routing extra and the live `trigger_id` on the
 * payload. We emit [CommandIntent.OpenAddParticipantModal] so the resolver opens the modal
 * synchronously before the trigger_id expires — mirroring [RescheduleMeetingContext]. Missing/malformed
 * extras or a blank trigger_id fall through to a no-op response; the resolver also guards the trigger_id.
 */
internal class AddParticipantContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.ADD_PARTICIPANT

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val meetingUid =
            interactionPayload.routingExtras
                .firstOrNull()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return successOutput()
        addIntent(
            CommandIntent.OpenAddParticipantModal(
                triggerId = interactionPayload.triggerId,
                meetingUid = meetingUid,
                requesterId = interactionPayload.user.id,
                channel = interactionPayload.channel.id,
            ),
        )
        return successOutput()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
}
