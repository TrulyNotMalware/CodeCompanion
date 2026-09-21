package dev.notypie.repository.meeting

import java.time.LocalDateTime

data class AgendaCandidateMeeting(
    val meetingId: Long,
    val title: String,
    val startAt: LocalDateTime,
    val attendingUserIds: List<String>,
)

interface AgendaDispatchRepository {
    fun claim(agendaDate: java.time.LocalDate): Boolean

    fun findAttendingMeetingsForDay(from: LocalDateTime, to: LocalDateTime): List<AgendaCandidateMeeting>
}
