package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarConnection
import java.time.Instant

data class ConnectionSaved(
    val connection: CalendarConnection,
    val replaced: CalendarConnection?,
)

interface GoogleCalendarConnectionRepository {
    fun find(userId: String): CalendarConnection?

    fun saveActive(
        userId: String,
        googleSubject: String?,
        googleEmail: String?,
        encryptedRefreshToken: String,
        now: Instant,
    ): ConnectionSaved

    fun delete(userId: String): Boolean

    fun hasActiveConnection(userId: String): Boolean

    fun markRevoked(
        userId: String,
        observedEncryptedRefreshToken: String,
        now: Instant,
        reason: String,
    ): Boolean
}
