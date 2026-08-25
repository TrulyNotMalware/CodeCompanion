package dev.notypie.repository.agent.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/** Terminal outcome of one agent turn, mirrored from the gateway's result variants. */
enum class AgentTurnOutcome {
    COMPLETED,
    BUSY,
    FAILED,
}

/**
 * Audit row for one AI-agent turn: who asked in which conversation, how it ended, and what it
 * cost. Token counts are the raw Anthropic usage numbers from the sidecar's terminal `done`
 * event — the Pod shares one Anthropic identity, so this table is what makes per-user/per-channel
 * consumption visible at all. Append-only; rows are never updated.
 */
@Entity(name = "agent_turn_history")
@Table(
    indexes = [
        Index(name = "idx_agent_turn_history_created_at", columnList = "created_at"),
        Index(name = "idx_agent_turn_history_session_key", columnList = "session_key"),
    ],
)
class AgentTurnHistorySchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "session_key", nullable = false, length = 160)
    val sessionKey: String,
    @field:Column(name = "requester_id", nullable = false, length = 255)
    val requesterId: String,
    @field:Column(name = "channel", nullable = false, length = 255)
    val channel: String,
    @field:Column(name = "idempotency_key", nullable = false, length = 36)
    val idempotencyKey: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "outcome", nullable = false, length = 16)
    val outcome: AgentTurnOutcome,
    @field:Column(name = "error_code", length = 64)
    val errorCode: String? = null,
    @field:Column(name = "input_tokens")
    val inputTokens: Long? = null,
    @field:Column(name = "output_tokens")
    val outputTokens: Long? = null,
    @field:Column(name = "duration_ms", nullable = false)
    val durationMs: Long,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
