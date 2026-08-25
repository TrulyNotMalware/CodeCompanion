package dev.notypie.domain.command.outbound

import java.util.UUID

sealed interface ModalForm {
    data class Reschedule(
        val meetingUid: UUID,
        val requesterId: String,
        // Ferried through the modal's private_metadata; a view_submission carries no channel.
        val channel: ConversationTarget,
    ) : ModalForm

    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
        // Ferried through the modal's private_metadata; a view_submission carries no channel.
        val channel: ConversationTarget,
    ) : ModalForm

    data class StandupFill(
        val sessionUid: UUID,
        val routineUid: UUID,
        val requesterId: String,
        // Origin notice, so the submission handler can chat.update the original prompt.
        val originNotice: MessageRef,
    ) : ModalForm

    data class StandupSetup(
        val creatorId: String,
        // Ferried through the modal's private_metadata; a view_submission carries no channel.
        val commandChannel: ConversationTarget,
    ) : ModalForm

    data class DeclineReason(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val meetingTitle: String,
        val originNotice: MessageRef?,
    ) : ModalForm

    /** Topics offered in the `/subscribe` modal; queried and mapped by the application service. */
    data class CveSubscribe(
        val topics: List<TopicOption>,
    ) : ModalForm

    /** The user's current subscriptions offered in the `/unsubscribe` modal. */
    data class CveUnsubscribe(
        val topics: List<TopicOption>,
    ) : ModalForm
}

/** A single modal option: [key] is the wire value (topic key), [label] the display name. */
data class TopicOption(
    val key: String,
    val label: String,
)
