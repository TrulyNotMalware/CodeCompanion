package dev.notypie.domain.meet.dto

import java.time.LocalDateTime

data class MeetingListDto(
    val meetings: List<Meeting>
)

data class Meeting(
    val creator: String,
    val title: String,
    val reason: String,

    val startAt: LocalDateTime,
    val participantIds: List<String>
)