package dev.notypie.repository.mcp

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.mcp.schema.McpToolCallOutcome
import java.time.LocalDateTime

data class McpToolCallRecord(
    val toolName: String,
    val requesterId: String,
    val sessionKey: String,
    val turnId: String,
    val resolvedRole: UserRole,
    val outcome: McpToolCallOutcome,
    val errorCode: String? = null,
    val argumentsJson: String? = null,
    val durationMs: Long,
)

data class ToolCallUsage(
    val toolName: String,
    val outcome: McpToolCallOutcome,
    val calls: Long,
)

interface McpToolCallHistoryRepository {
    fun record(call: McpToolCallRecord)

    fun countByToolSince(since: LocalDateTime): List<ToolCallUsage>
}
