package dev.notypie.application.service.meeting

import dev.notypie.repository.meeting.AgendaCandidateMeeting
import java.time.LocalDateTime

fun createAgendaItem(
    startAt: LocalDateTime = LocalDateTime.of(2026, 5, 4, 10, 0),
    title: String = "Sprint Planning",
): AgendaItem =
    AgendaItem(
        startAt = startAt,
        title = title,
    )

fun createAgendaCandidateMeeting(
    meetingId: Long = 1L,
    title: String = "Sprint Planning",
    startAt: LocalDateTime = LocalDateTime.of(2026, 5, 4, 10, 0),
    attendingUserIds: List<String> = listOf("U_A"),
): AgendaCandidateMeeting =
    AgendaCandidateMeeting(
        meetingId = meetingId,
        title = title,
        startAt = startAt,
        attendingUserIds = attendingUserIds,
    )
