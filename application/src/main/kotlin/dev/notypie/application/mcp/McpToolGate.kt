package dev.notypie.application.mcp

import dev.notypie.application.security.mcp.SCOPED_TURN_TOKEN_CONTEXT_KEY
import dev.notypie.application.security.mcp.ScopedTurnToken
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.mcp.McpToolCallRecord
import dev.notypie.repository.mcp.schema.McpToolCallOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema.CallToolResult

private val log = KotlinLogging.logger {}

class McpToolGate(
    private val commandRoleResolver: CommandRoleResolver,
    private val mcpToolCallHistoryRepository: McpToolCallHistoryRepository,
) {
    fun execute(
        transportContext: McpTransportContext,
        toolName: String,
        requiredPermission: CommandPermission,
        argumentsSummary: String? = null,
        body: (ScopedTurnToken) -> String,
    ): CallToolResult {
        val token =
            transportContext.get(SCOPED_TURN_TOKEN_CONTEXT_KEY) as? ScopedTurnToken
                ?: return errorResult(text = "Unauthenticated tool call.")
                    .also { log.warn { "MCP tool call without a verified turn token: tool=$toolName" } }

        val startedAt = System.nanoTime()

        fun audit(role: UserRole, outcome: McpToolCallOutcome, errorCode: String? = null) =
            runCatching {
                mcpToolCallHistoryRepository.record(
                    call =
                        McpToolCallRecord(
                            toolName = toolName,
                            requesterId = token.userId,
                            sessionKey = token.sessionKey,
                            turnId = token.turnId,
                            resolvedRole = role,
                            outcome = outcome,
                            errorCode = errorCode,
                            argumentsJson = argumentsSummary,
                            durationMs = (System.nanoTime() - startedAt) / 1_000_000L,
                        ),
                )
            }.onFailure { log.error(it) { "MCP tool audit write failed: tool=$toolName outcome=$outcome" } }

        val resolvedRole =
            runCatching { commandRoleResolver.resolve(userId = token.userId) }
                .getOrElse { failure ->
                    log.error(failure) { "MCP role resolution failed: tool=$toolName" }
                    audit(
                        role = UserRole.USER,
                        outcome = McpToolCallOutcome.FAILED,
                        errorCode = failure.javaClass.simpleName,
                    )
                    return errorResult(text = "`$toolName` failed to execute. Try again or contact an admin.")
                }

        if (!resolvedRole.grants(permission = requiredPermission)) {
            audit(role = resolvedRole, outcome = McpToolCallOutcome.DENIED)
            return errorResult(
                text =
                    "You don't have permission to use `$toolName` " +
                        "(requires ${requiredPermission.name.lowercase()}). Ask an admin to grant you access.",
            )
        }

        return runCatching { body(token) }
            .fold(
                onSuccess = { text ->
                    audit(role = resolvedRole, outcome = McpToolCallOutcome.COMPLETED)
                    CallToolResult
                        .builder()
                        .addTextContent(text)
                        .isError(false)
                        .build()
                },
                onFailure = { failure ->
                    log.error(failure) { "MCP tool execution failed: tool=$toolName" }
                    audit(
                        role = resolvedRole,
                        outcome = McpToolCallOutcome.FAILED,
                        errorCode = failure.javaClass.simpleName,
                    )
                    errorResult(text = "`$toolName` failed to execute. Try again or contact an admin.")
                },
            )
    }

    private fun errorResult(text: String): CallToolResult =
        CallToolResult
            .builder()
            .addTextContent(text)
            .isError(true)
            .build()
}
