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

internal class RescheduleMeetingContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.RESCHEDULE_MEETING

    /**
     * Reschedule button on `/meetup list` carries `<listIdempotencyKey>,RESCHEDULE_MEETING,<meetingUid>`.
     * The parser surfaces the meetingUid as the first routing extra and the live `trigger_id` on the
     * payload. We emit [CommandIntent.OpenRescheduleMeetingModal] so the resolver opens the modal
     * synchronously before the trigger_id expires. Missing/malformed extras or a blank trigger_id
     * fall through to a no-op response — the resolver also guards against a blank trigger_id.
     */
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val meetingUid =
            interactionPayload.routingExtras
                .firstOrNull()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return successOutput()
        addIntent(
            CommandIntent.OpenRescheduleMeetingModal(
                triggerId = interactionPayload.triggerId,
                meetingUid = meetingUid,
                requesterId = interactionPayload.user.id,
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
