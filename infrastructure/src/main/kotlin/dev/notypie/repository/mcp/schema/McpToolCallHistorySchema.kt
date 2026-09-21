package dev.notypie.repository.mcp.schema

import dev.notypie.domain.command.authorization.UserRole
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

enum class McpToolCallOutcome {
    COMPLETED,
    DENIED,
    FAILED,
}

@Entity(name = "mcp_tool_call_history")
@Table(
    indexes = [
        Index(name = "idx_mcp_tool_call_history_created_at", columnList = "created_at"),
        Index(name = "idx_mcp_tool_call_history_requester_id", columnList = "requester_id"),
    ],
)
class McpToolCallHistorySchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "tool_name", nullable = false, length = 64)
    val toolName: String,
    @field:Column(name = "requester_id", nullable = false, length = 255)
    val requesterId: String,
    @field:Column(name = "session_key", nullable = false, length = 160)
    val sessionKey: String,
    @field:Column(name = "turn_id", nullable = false, length = 36)
    val turnId: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "resolved_role", nullable = false, length = 16)
    val resolvedRole: UserRole,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "outcome", nullable = false, length = 16)
    val outcome: McpToolCallOutcome,
    @field:Column(name = "error_code", length = 64)
    val errorCode: String? = null,
    @field:Column(name = "arguments_json", length = 2000)
    val argumentsJson: String? = null,
    @field:Column(name = "duration_ms", nullable = false)
    val durationMs: Long,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
