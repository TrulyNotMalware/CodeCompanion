package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

internal class MeetingApprovalResponseContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val accepted = interactionPayload.currentAction.type == ActionElementTypes.APPLY_BUTTON

        // The meeting's idempotencyKey is embedded by the outbound notice template in the
        // tokenized routing payload (see RequestMeetingContext.sendNotice →
        // ApprovalContents.idempotencyKey → SlackApiEventConstructor). For DM-delivered
        // non-ephemeral notice messages, SlackInteractionRequestParser extracts it from
        // `message.text`, not the button `action.value`; both fields currently carry the
        // same tokenized string, so payload.idempotencyKey matches the meeting's key.
        addIntent(
            CommandIntent.MeetingAttendanceUpdate(
                meetingIdempotencyKey = UUID.fromString(interactionPayload.idempotencyKey),
                participantUserId = interactionPayload.user.id,
                isAttending = accepted,
                absentReason = if (accepted) RejectReason.ATTENDING else RejectReason.OTHER,
            ),
        )

        val message =
            if (accepted) {
                "You accepted the meeting invitation."
            } else {
                "You declined the meeting invitation."
            }
        return interactionSuccessResponse(
            responseUrl = interactionPayload.responseUrl,
            mkdMessage = message,
        )
    }
}
