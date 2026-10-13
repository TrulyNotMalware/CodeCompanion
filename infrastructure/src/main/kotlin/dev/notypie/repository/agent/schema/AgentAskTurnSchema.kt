package dev.notypie.repository.agent.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.LocalDateTime

enum class AgentAskTurnStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    ABANDONED,
}

@Entity(name = "agent_ask_turn")
@Table(
    indexes = [
        Index(name = "idx_agent_ask_turn_status_updated_at", columnList = "status, updated_at"),
    ],
)
class AgentAskTurnSchema(
    @field:Id
    @field:Column(name = "event_id", nullable = false, length = 36)
    val eventId: String,
    @field:Column(name = "message_ts", nullable = false, length = 32)
    val messageTs: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 16)
    val status: AgentAskTurnStatus = AgentAskTurnStatus.PENDING,
    @field:Column(name = "attempt_count", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0")
    val attemptCount: Int = 0,
    @field:Column(name = "run_count", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0")
    val runCount: Int = 0,
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,
    @field:Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime,
) : Persistable<String> {
    @field:Transient
    private var newRow: Boolean = true

    override fun getId(): String = eventId

    override fun isNew(): Boolean = newRow

    @PostPersist
    @PostLoad
    protected fun markPersisted() {
        newRow = false
    }
}
