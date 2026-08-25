package dev.notypie.repository.mcp

import dev.notypie.repository.mcp.schema.McpToolCallHistorySchema
import org.springframework.data.jpa.repository.JpaRepository

interface JpaMcpToolCallHistoryRepository : JpaRepository<McpToolCallHistorySchema, Long>
