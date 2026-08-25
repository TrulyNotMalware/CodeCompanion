package dev.notypie.repository.agent.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Maps a stable conversation key (`"<channel>:<thread_ts>"` for Slack threads) to the AI backend's
 * own session id. The backend only resumes conversational context when the previous turn's session
 * id is echoed back, so this row is what makes a Slack thread behave as one continuous conversation.
 *
 * The unique key on `session_key` enforces "one backend session per conversation" at the DB level;
 * concurrent turns for the same key are already rejected upstream by the sidecar's per-sessionKey
 * gate, so the plain read-modify-write in the repository is race-safe in practice.
 */
@Entity(name = "agent_session")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_agent_session_session_key", columnNames = ["session_key"]),
    ],
)
class AgentSessionSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "session_key", nullable = false, length = 160)
    val sessionKey: String,
    @field:Column(name = "provider_session_id", nullable = false, length = 255)
    var providerSessionId: String,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)
