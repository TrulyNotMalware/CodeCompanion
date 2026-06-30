package dev.notypie.domain.command.outbound

import java.time.LocalDateTime
import java.util.UUID

sealed interface ModalForm {
    data class Reschedule(
        val meetingUid: UUID,
        val requesterId: String,
        val currentStartAt: LocalDateTime,
    ) : ModalForm

    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
    ) : ModalForm

    data class StandupFill(
        val sessionUid: UUID,
        val routineUid: UUID,
        val requesterId: String,
    ) : ModalForm

    data class StandupSetup(
        val creatorId: String,
    ) : ModalForm

    data class DeclineReason(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val meetingTitle: String,
        val originNotice: MessageRef?,
    ) : ModalForm
}
