package dev.notypie.repository.mcp

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.mcp.schema.McpToolCallOutcome

/** One finished MCP tool dispatch, ready to be appended to the audit table. */
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

interface McpToolCallHistoryRepository {
    fun record(call: McpToolCallRecord)
}
