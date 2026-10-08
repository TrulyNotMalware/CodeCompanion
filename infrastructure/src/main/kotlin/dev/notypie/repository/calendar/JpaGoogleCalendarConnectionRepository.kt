package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.GoogleCalendarConnectionSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface JpaGoogleCalendarConnectionRepository : JpaRepository<GoogleCalendarConnectionSchema, Long> {
    fun findBySlackUserId(slackUserId: String): GoogleCalendarConnectionSchema?

    fun deleteBySlackUserId(slackUserId: String): Long
}
