package dev.notypie.schema

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.agent.AgentTurnOutcomeUsage
import dev.notypie.repository.agent.RequesterTurnUsage
import dev.notypie.repository.agent.schema.AgentTurnHistorySchema
import dev.notypie.repository.agent.schema.AgentTurnOutcome
import dev.notypie.repository.mcp.ToolCallUsage
import dev.notypie.repository.mcp.schema.McpToolCallHistorySchema
import dev.notypie.repository.mcp.schema.McpToolCallOutcome
import java.util.UUID

fun createAgentTurnHistorySchema(
    id: Long = 0L,
    sessionKey: String = "thread-1",
    requesterId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: String = UUID.randomUUID().toString(),
    outcome: AgentTurnOutcome = AgentTurnOutcome.COMPLETED,
    errorCode: String? = null,
    inputTokens: Long? = 100L,
    outputTokens: Long? = 10L,
    durationMs: Long = 1_000L,
): AgentTurnHistorySchema =
    AgentTurnHistorySchema(
        id = id,
        sessionKey = sessionKey,
        requesterId = requesterId,
        channel = channel,
        idempotencyKey = idempotencyKey,
        outcome = outcome,
        errorCode = errorCode,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        durationMs = durationMs,
    )

fun createMcpToolCallHistorySchema(
    id: Long = 0L,
    toolName: String = "get_status",
    requesterId: String = TEST_USER_ID,
    sessionKey: String = "thread-1",
    turnId: String = UUID.randomUUID().toString(),
    resolvedRole: UserRole = UserRole.DEVELOPER,
    outcome: McpToolCallOutcome = McpToolCallOutcome.COMPLETED,
    errorCode: String? = null,
    argumentsJson: String? = null,
    durationMs: Long = 50L,
): McpToolCallHistorySchema =
    McpToolCallHistorySchema(
        id = id,
        toolName = toolName,
        requesterId = requesterId,
        sessionKey = sessionKey,
        turnId = turnId,
        resolvedRole = resolvedRole,
        outcome = outcome,
        errorCode = errorCode,
        argumentsJson = argumentsJson,
        durationMs = durationMs,
    )

fun createAgentTurnOutcomeUsage(
    outcome: AgentTurnOutcome = AgentTurnOutcome.COMPLETED,
    turns: Long = 1L,
    inputTokens: Long = 100L,
    outputTokens: Long = 10L,
    totalDurationMs: Long = 1_000L,
): AgentTurnOutcomeUsage =
    AgentTurnOutcomeUsage(
        outcome = outcome,
        turns = turns,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalDurationMs = totalDurationMs,
    )

fun createRequesterTurnUsage(
    requesterId: String = TEST_USER_ID,
    turns: Long = 1L,
    inputTokens: Long = 100L,
    outputTokens: Long = 10L,
): RequesterTurnUsage =
    RequesterTurnUsage(
        requesterId = requesterId,
        turns = turns,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )

fun createToolCallUsage(
    toolName: String = "get_status",
    outcome: McpToolCallOutcome = McpToolCallOutcome.COMPLETED,
    calls: Long = 1L,
): ToolCallUsage =
    ToolCallUsage(
        toolName = toolName,
        outcome = outcome,
        calls = calls,
    )
