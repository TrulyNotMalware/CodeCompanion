package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface JpaMeetingRepository: JpaRepository<MeetingSchema, Long> {

    @Query(
        """
            SELECT m FROM meetings m 
            JOIN FETCH m.participants
            WHERE m.id = :meetingId
        """
    )
    fun findMeetingWithParticipants(
        @Param("meetingId") meetingId: Long,
    ): MeetingSchema?
}