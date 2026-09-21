package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto

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
