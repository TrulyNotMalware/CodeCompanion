package dev.notypie.repository.meeting

import java.time.LocalDateTime

/**
 * Projection of a non-canceled meeting that falls on the agenda day, carrying the attending
 * participant ids so the scheduler can group meetings per user without an extra round-trip.
 */
data class AgendaCandidateMeeting(
    val meetingId: Long,
    val title: String,
    val startAt: LocalDateTime,
    val attendingUserIds: List<String>,
)

/**
 * Domain-facing repository for the daily-agenda feature. The claim side is the once-per-day
 * idempotency gate (`INSERT IGNORE` on the agenda date); the read side returns the non-canceled
 * meetings on the agenda day with their attending participants.
 */
interface AgendaDispatchRepository {
    /**
     * Atomically claims the agenda for [agendaDate]. Returns true iff *this* call inserted the
     * row (it owns today's agenda); false means a concurrent tick already claimed it.
     */
    fun claim(agendaDate: java.time.LocalDate): Boolean

    /**
     * Non-canceled meetings whose `startAt` falls in `[from, to)`, each with its attending
     * participant ids. Reuses the reminder window query, which already excludes canceled meetings
     * and eagerly fetches participants.
     */
    fun findAttendingMeetingsForDay(from: LocalDateTime, to: LocalDateTime): List<AgendaCandidateMeeting>
}
