package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.entity.RejectReason
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
                    // Meeting title, surfaced as the first routing extra; blank omits the title section.
                    meetingTitle = interactionPayload.routingExtras.firstOrNull().orEmpty(),
                    // Notice DM channel + message_ts; let the submission handler chat.update the notice.
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
     * Records a provisional decline with [RejectReason.OTHER], then opens the reason-picker modal.
     * The provisional write honors the Deny intent even if `views.open` fails, the user cancels the
     * modal, or `view_submission` never arrives; a later submission overwrites it with the real reason.
     *
     * Order matters: [OutboundMessage.OpenModal] is emitted first so `views.open` fires before
     * trigger_id expires (3s). The provisional update runs at BEFORE_COMMIT, off the trigger window.
     *
     * Intentionally does NOT call `interactionSuccessResponse`: replacing the notice with a
     * "You declined" banner before a reason is confirmed would destroy context if the modal fails.
     */
    private fun handleDecline(
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        triggerId: String,
        meetingTitle: String,
        noticeChannel: String,
        noticeMessageTs: String,
    ): CommandOutput {
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = triggerId),
                form =
                    ModalForm.DeclineReason(
                        meetingIdempotencyKey = meetingIdempotencyKey,
                        participantUserId = participantUserId,
                        meetingTitle = meetingTitle,
                        originNotice =
                            MessageRef(
                                conversation = ConversationTarget(id = noticeChannel),
                                messageId = noticeMessageTs,
                            ),
                    ),
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
