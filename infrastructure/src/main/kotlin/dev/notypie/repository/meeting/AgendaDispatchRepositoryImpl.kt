package dev.notypie.repository.meeting

import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

open class AgendaDispatchRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
    private val jpaAgendaDispatchRepository: JpaAgendaDispatchRepository,
) : AgendaDispatchRepository {
    @Transactional
    override fun claim(agendaDate: LocalDate): Boolean = jpaAgendaDispatchRepository.claimAgenda(date = agendaDate) == 1

    override fun findAttendingMeetingsForDay(from: LocalDateTime, to: LocalDateTime): List<AgendaCandidateMeeting> =
        jpaMeetingRepository
            .findActiveByStartAtBetween(startAt = from, endAt = to)
            .map { meeting ->
                AgendaCandidateMeeting(
                    meetingId = meeting.id,
                    title = meeting.name,
                    startAt = meeting.startAt,
                    attendingUserIds =
                        meeting.participants
                            .filter { it.isAttending }
                            .map { it.userId },
                )
            }
}
