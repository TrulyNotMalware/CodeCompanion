package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.repository.calendar.schema.GoogleCalendarConnectionSchema
import dev.notypie.repository.calendar.schema.toCalendarConnection
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

open class GoogleCalendarConnectionRepositoryImpl(
    private val jpaGoogleCalendarConnectionRepository: JpaGoogleCalendarConnectionRepository,
) : GoogleCalendarConnectionRepository {
    @Transactional(readOnly = true)
    override fun find(userId: String): CalendarConnection? =
        jpaGoogleCalendarConnectionRepository.findBySlackUserId(slackUserId = userId)?.toCalendarConnection()

    @Transactional
    override fun saveActive(
        userId: String,
        googleSubject: String?,
        googleEmail: String?,
        encryptedRefreshToken: String,
        now: Instant,
    ): ConnectionSaved {
        val existing = jpaGoogleCalendarConnectionRepository.findBySlackUserId(slackUserId = userId)
        val replaced = existing?.toCalendarConnection()
        val schema =
            if (existing == null) {
                GoogleCalendarConnectionSchema(
                    slackUserId = userId,
                    googleSubject = googleSubject,
                    googleEmail = googleEmail,
                    encryptedRefreshToken = encryptedRefreshToken,
                    connectedAt = now,
                )
            } else {
                existing.apply {
                    reconnect(
                        googleSubject = googleSubject,
                        googleEmail = googleEmail,
                        encryptedRefreshToken = encryptedRefreshToken,
                        now = now,
                    )
                }
            }
        return ConnectionSaved(
            connection = jpaGoogleCalendarConnectionRepository.save(schema).toCalendarConnection(),
            replaced = replaced,
        )
    }

    @Transactional
    override fun delete(userId: String): Boolean =
        jpaGoogleCalendarConnectionRepository.deleteBySlackUserId(slackUserId = userId) > 0L
}
