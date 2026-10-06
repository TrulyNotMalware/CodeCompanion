package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto

sealed interface RescheduleResult {
    data class Rescheduled(
        val meeting: MeetingDto,
    ) : RescheduleResult

    data object AlreadyAtRequestedTime : RescheduleResult

    data object NotAuthorized : RescheduleResult
}
