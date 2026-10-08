package dev.notypie.repository.calendar.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime

@Entity(name = "google_calendar_connection")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_google_calendar_connection_user", columnNames = ["slack_user_id"]),
    ],
)
class GoogleCalendarConnectionSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "slack_user_id", nullable = false, length = 64)
    val slackUserId: String,
    googleSubject: String?,
    googleEmail: String?,
    encryptedRefreshToken: String,
    connectedAt: Instant,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
) {
    @field:Column(name = "google_subject", length = 255)
    var googleSubject: String? = googleSubject
        protected set

    @field:Column(name = "google_email", length = 255)
    var googleEmail: String? = googleEmail
        protected set

    @field:Column(name = "refresh_token_enc", nullable = false, columnDefinition = "TEXT")
    var encryptedRefreshToken: String = encryptedRefreshToken
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 16)
    var status: CalendarConnectionStatus = CalendarConnectionStatus.ACTIVE
        protected set

    @field:Column(name = "connected_at", nullable = false)
    var connectedAt: Instant = connectedAt
        protected set

    @field:Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    @field:Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null
        protected set

    fun reconnect(
        googleSubject: String?,
        googleEmail: String?,
        encryptedRefreshToken: String,
        now: Instant,
    ) {
        this.googleSubject = googleSubject
        this.googleEmail = googleEmail
        this.encryptedRefreshToken = encryptedRefreshToken
        this.status = CalendarConnectionStatus.ACTIVE
        this.connectedAt = now
        this.revokedAt = null
        this.lastError = null
    }

    fun revoke(now: Instant, reason: String?) {
        this.status = CalendarConnectionStatus.REVOKED
        this.revokedAt = now
        this.lastError = reason
    }
}

fun GoogleCalendarConnectionSchema.toCalendarConnection(): CalendarConnection =
    CalendarConnection(
        slackUserId = slackUserId,
        googleSubject = googleSubject,
        googleEmail = googleEmail,
        encryptedRefreshToken = encryptedRefreshToken,
        status = status,
        connectedAt = connectedAt,
        revokedAt = revokedAt,
        lastError = lastError,
    )

data class CalendarConnection(
    val slackUserId: String,
    val googleSubject: String?,
    val googleEmail: String?,
    val encryptedRefreshToken: String,
    val status: CalendarConnectionStatus,
    val connectedAt: Instant,
    val revokedAt: Instant?,
    val lastError: String?,
) {
    val isActive: Boolean get() = status == CalendarConnectionStatus.ACTIVE

    override fun toString(): String =
        "CalendarConnection(slackUserId=$slackUserId, googleSubject=$googleSubject, googleEmail=$googleEmail, " +
            "status=$status, connectedAt=$connectedAt, revokedAt=$revokedAt, lastError=$lastError)"
}
