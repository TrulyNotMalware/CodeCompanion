package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto

/**
 * Outcome of [MeetingRepository.addParticipants]. Collapses the host-only authorization, dedup,
 * already-started, and `MAX_PARTICIPANTS` capacity checks into a single value the application
 * listener maps to a user-facing ephemeral. [addedUserIds] is non-empty only for [Outcome.ADDED];
 * [meeting] carries the loaded meeting (idempotencyKey/title) so the listener can re-use the same
 * Accept/Decline approval notice the creation flow sends.
 */
data class AddParticipantResult(
    val outcome: Outcome,
    val addedUserIds: List<String> = emptyList(),
    val meeting: MeetingDto? = null,
) {
    enum class Outcome {
        ADDED,
        NO_NEW_PARTICIPANTS,
        OVER_CAPACITY,
        MEETING_STARTED,
        NOT_AUTHORIZED,
        MEETING_NOT_FOUND,
    }
}
