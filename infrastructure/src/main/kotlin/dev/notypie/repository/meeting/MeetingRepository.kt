package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MeetingRepository: JpaRepository<MeetingSchema, Long> {

}