package dev.notypie.domain.command.outbound

import java.util.UUID

sealed interface ModalForm {
    data class Reschedule(
        val meetingUid: UUID,
        val requesterId: String,
        // Channel the `/meetup list` message lives in, ferried through the modal's private_metadata so
        // the host's confirmation posts back in-channel (a view_submission carries no channel).
        val channel: ConversationTarget,
    ) : ModalForm

    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
        // Channel ferried through the modal's private_metadata so the host's confirmation ephemeral
        // posts back into the `/meetup list` channel rather than failing on the channel-less submission.
        val channel: ConversationTarget,
    ) : ModalForm

    data class StandupFill(
        val sessionUid: UUID,
        val routineUid: UUID,
        val requesterId: String,
        // Channel + message_ts of the originating notice, carried so the submission handler can
        // chat.update the original prompt after the user submits.
        val originNotice: MessageRef,
    ) : ModalForm

    data class StandupSetup(
        val creatorId: String,
        // Channel carried into the modal's private_metadata so the submission handler knows where to
        // persist + post the confirmation.
        val commandChannel: ConversationTarget,
    ) : ModalForm

    data class DeclineReason(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val meetingTitle: String,
        val originNotice: MessageRef?,
    ) : ModalForm
}
