package dev.notypie.repository.mcp

import dev.notypie.repository.mcp.schema.McpToolCallHistorySchema

class McpToolCallHistoryRepositoryImpl(
    private val jpaMcpToolCallHistoryRepository: JpaMcpToolCallHistoryRepository,
) : McpToolCallHistoryRepository {
    override fun record(call: McpToolCallRecord) {
        jpaMcpToolCallHistoryRepository.save(
            McpToolCallHistorySchema(
                toolName = call.toolName,
                requesterId = call.requesterId,
                sessionKey = call.sessionKey,
                turnId = call.turnId,
                resolvedRole = call.resolvedRole,
                outcome = call.outcome,
                errorCode = call.errorCode,
                argumentsJson = call.argumentsJson,
                durationMs = call.durationMs,
            ),
        )
    }
}
