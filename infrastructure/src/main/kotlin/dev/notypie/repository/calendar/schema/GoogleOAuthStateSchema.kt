package dev.notypie.repository.calendar.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.CreationTimestamp
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.time.LocalDateTime

@Entity(name = "google_oauth_state")
@Table(
    indexes = [
        Index(name = "idx_google_oauth_state_expires_at", columnList = "expires_at"),
        Index(name = "idx_google_oauth_state_slack_user_id", columnList = "slack_user_id"),
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
) : Persistable<String> {
    @field:Transient
    private var newRow: Boolean = true

    override fun getId(): String = state

    override fun isNew(): Boolean = newRow

    @PostPersist
    @PostLoad
    protected fun markPersisted() {
        newRow = false
    }
}
