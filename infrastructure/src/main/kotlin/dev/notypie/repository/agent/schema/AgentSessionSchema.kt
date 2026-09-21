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
