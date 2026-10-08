package dev.notypie.repository.calendar.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.time.LocalDateTime

@Entity(name = "google_oauth_state")
@Table(
    indexes = [
        Index(name = "idx_google_oauth_state_expires_at", columnList = "expires_at"),
    ],
)
class GoogleOAuthStateSchema(
    @field:Id
    @field:Column(name = "state", nullable = false, length = 64)
    val state: String,
    @field:Column(name = "slack_user_id", nullable = false, length = 64)
    val slackUserId: String,
    @field:Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @field:Column(name = "consumed_at")
    val consumedAt: Instant? = null,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
