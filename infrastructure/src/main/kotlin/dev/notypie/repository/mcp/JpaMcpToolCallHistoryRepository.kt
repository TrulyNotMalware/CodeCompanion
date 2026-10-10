package dev.notypie.repository.mcp

import dev.notypie.repository.mcp.schema.McpToolCallHistorySchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface JpaMcpToolCallHistoryRepository : JpaRepository<McpToolCallHistorySchema, Long> {
    @Query(
        """
        SELECT new dev.notypie.repository.mcp.ToolCallUsage(c.toolName, c.outcome, COUNT(c))
        FROM mcp_tool_call_history c
        WHERE c.createdAt >= :since
        GROUP BY c.toolName, c.outcome
        ORDER BY c.toolName ASC, c.outcome ASC
        """,
    )
    fun countByToolSince(
        @Param("since") since: LocalDateTime,
    ): List<ToolCallUsage>
}
