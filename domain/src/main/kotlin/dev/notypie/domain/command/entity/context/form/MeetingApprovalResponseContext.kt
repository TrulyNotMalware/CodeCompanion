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
        val meetingIdempotencyKey = UUID.fromString(interactionPayload.idempotencyKey)
        val participantUserId = interactionPayload.user.id
        return when (interactionPayload.currentAction.type) {
            ActionElementTypes.APPLY_BUTTON ->
                handleAccept(
                    meetingIdempotencyKey = meetingIdempotencyKey,
                    participantUserId = participantUserId,
                    responseUrl = interactionPayload.responseUrl,
                )

            ActionElementTypes.REJECT_BUTTON ->
                handleDecline(
                    meetingIdempotencyKey = meetingIdempotencyKey,
                    participantUserId = participantUserId,
                    triggerId = interactionPayload.triggerId,
                    // The notice was sent with `ApprovalContents.subTitle` (meeting title)
                    // propagated through the routing text by SlackIntentResolver, so the
                    // parser surfaces it as the first extra. Blank/missing falls through to ""
                    // and the modal template simply omits the title section.
                    meetingTitle = interactionPayload.routingExtras.firstOrNull().orEmpty(),
                    // Channel + message_ts of the notice DM. Carried through the modal's
                    // private_metadata so the submission handler can chat.update the notice.
                    // Blank means we can't update (synthesized test payloads, rare races).
                    noticeChannel = interactionPayload.channel.id,
                    noticeMessageTs = interactionPayload.container.messageTs.orEmpty(),
                )

            else -> interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
        }
    }

    private fun handleAccept(
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        responseUrl: String,
    ): CommandOutput {
        addIntent(
            CommandIntent.MeetingAttendanceUpdate(
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
                isAttending = true,
                absentReason = RejectReason.ATTENDING,
            ),
        )
        return interactionSuccessResponse(
            responseUrl = responseUrl,
            mkdMessage = "You accepted the meeting invitation.",
        )
    }

    /**
     * Records a provisional decline with [RejectReason.OTHER] and opens the reason-picker
     * modal. The provisional write guarantees the user's Deny intent is always honored — even
     * if `views.open` fails, if the user cancels the modal (X / Esc), or if `view_submission`
     * never reaches us. When the user does submit the modal, [DeclineReasonSubmissionContext]
     * emits a second [CommandIntent.MeetingAttendanceUpdate] that overwrites the provisional
     * row with the real reason.
     *
     * Intent order matters: [CommandIntent.OpenDeclineReasonModal] is emitted first so that
     * [dev.notypie.impl.command.SlackIntentResolver] resolves it to [OpenViewEvent] and
     * dispatches `views.open` before any unrelated intent can queue up behind it — trigger_id
     * expires 3 seconds after Slack issues it. The provisional update follows and is persisted
     * at BEFORE_COMMIT via [dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent],
     * which doesn't compete with the trigger window because it runs at transaction commit.
     *
     * We intentionally do NOT call `interactionSuccessResponse` — replacing the original notice
     * with a "You declined" banner before the user has actually confirmed a reason would
     * destroy the context they need if the modal fails to open.
     */
    private fun handleDecline(
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        triggerId: String,
        meetingTitle: String,
        noticeChannel: String,
        noticeMessageTs: String,
    ): CommandOutput {
        addIntent(
            CommandIntent.OpenDeclineReasonModal(
                triggerId = triggerId,
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
                meetingTitle = meetingTitle,
                noticeChannel = noticeChannel,
                noticeMessageTs = noticeMessageTs,
            ),
        )
        addIntent(
            CommandIntent.MeetingAttendanceUpdate(
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
                isAttending = false,
                absentReason = RejectReason.OTHER,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
