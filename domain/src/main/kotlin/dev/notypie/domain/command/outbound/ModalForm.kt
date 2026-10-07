package dev.notypie.domain.command.outbound

import java.util.UUID

sealed interface ModalForm {
    data class Reschedule(
        val meetingUid: UUID,
        val requesterId: String,
        val channel: ConversationTarget,
    ) : ModalForm

    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
        val channel: ConversationTarget,
    ) : ModalForm

    data class StandupFill(
        val sessionUid: UUID,
        val routineUid: UUID,
        val requesterId: String,
        val originNotice: MessageRef,
    ) : ModalForm

    data class StandupSetup(
        val creatorId: String,
        val commandChannel: ConversationTarget,
    ) : ModalForm

    data class DeclineReason(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val meetingTitle: String,
        val originNotice: MessageRef?,
    ) : ModalForm

    data class CveSubscribe(
        val topics: List<TopicOption>,
    ) : ModalForm

    data class CveUnsubscribe(
        val topics: List<TopicOption>,
    ) : ModalForm
}

data class TopicOption(
    val key: String,
    val label: String,
)
