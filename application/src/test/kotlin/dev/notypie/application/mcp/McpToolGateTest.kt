package dev.notypie.application.mcp

import dev.notypie.application.security.mcp.SCOPED_TURN_TOKEN_CONTEXT_KEY
import dev.notypie.application.security.mcp.ScopedTurnToken
import dev.notypie.application.security.mcp.createScopedTurnToken
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.mcp.McpToolCallRecord
import dev.notypie.repository.mcp.schema.McpToolCallOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

private fun CallToolResult.text(): String = content().first().shouldBeInstanceOf<TextContent>().text()

private fun contextWith(token: ScopedTurnToken): McpTransportContext =
    McpTransportContext.create(mapOf(SCOPED_TURN_TOKEN_CONTEXT_KEY to token))

class McpToolGateTest :
    BehaviorSpec({
        val token = createScopedTurnToken()

        fun gateWith(
            role: UserRole,
            auditRepository: McpToolCallHistoryRepository = mockk(relaxed = true),
        ): McpToolGate {
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = token.userId) } returns role
            return McpToolGate(
                commandRoleResolver = roleResolver,
                mcpToolCallHistoryRepository = auditRepository,
            )
        }

        given("a caller whose role lacks the required permission") {
            val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
            val gate = gateWith(role = UserRole.USER, auditRepository = auditRepository)

            `when`("a tool requiring OPERATIONS is dispatched") {
                val result =
                    gate.execute(
                        transportContext = contextWith(token = token),
                        toolName = "get_status",
                        requiredPermission = CommandPermission.OPERATIONS,
                    ) { "must not run" }

                then("the result is a tool error naming the missing permission") {
                    result.isError shouldBe true
                    result.text() shouldContain "get_status"
                    result.text() shouldContain "operations"
                }

                then("a DENIED audit row is recorded with the resolved role") {
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.outcome shouldBe McpToolCallOutcome.DENIED
                    recorded.captured.resolvedRole shouldBe UserRole.USER
                    recorded.captured.requesterId shouldBe token.userId
                }
            }
        }

        given("a caller whose role grants the required permission") {
            val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
            val gate = gateWith(role = UserRole.DEVELOPER, auditRepository = auditRepository)

            `when`("a tool requiring OPERATIONS is dispatched") {
                val result =
                    gate.execute(
                        transportContext = contextWith(token = token),
                        toolName = "get_status",
                        requiredPermission = CommandPermission.OPERATIONS,
                        argumentsSummary = """{"probe":true}""",
                    ) { "tool output" }

                then("the body result is returned as a non-error text content") {
                    result.isError shouldBe false
                    result.text() shouldBe "tool output"
                }

                then("a COMPLETED audit row carries the turn linkage and duration") {
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.outcome shouldBe McpToolCallOutcome.COMPLETED
                    recorded.captured.turnId shouldBe token.turnId
                    recorded.captured.sessionKey shouldBe token.sessionKey
                    recorded.captured.argumentsJson shouldBe """{"probe":true}"""
                    recorded.captured.durationMs shouldBeGreaterThanOrEqual 0L
                }
            }
        }

        given("a transport context without a verified token") {
            val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
            val roleResolver = mockk<CommandRoleResolver>()
            val gate =
                McpToolGate(
                    commandRoleResolver = roleResolver,
                    mcpToolCallHistoryRepository = auditRepository,
                )

            `when`("any tool is dispatched") {
                val result =
                    gate.execute(
                        transportContext = McpTransportContext.EMPTY,
                        toolName = "get_status",
                        requiredPermission = CommandPermission.OPERATIONS,
                    ) { "must not run" }

                then("it fails closed without resolving a role or auditing") {
                    result.isError shouldBe true
                    result.text() shouldContain "Unauthenticated"
                    verify(exactly = 0) { roleResolver.resolve(userId = any()) }
                    verify(exactly = 0) { auditRepository.record(call = any()) }
                }
            }
        }

        given("a tool body that throws") {
            val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
            val gate = gateWith(role = UserRole.ADMIN, auditRepository = auditRepository)

            `when`("dispatched") {
                val result =
                    gate.execute(
                        transportContext = contextWith(token = token),
                        toolName = "list_roles",
                        requiredPermission = CommandPermission.ADMINISTRATION,
                    ) { throw IllegalStateException("boom") }

                then("the result is a tool error and a FAILED row records the error code") {
                    result.isError shouldBe true
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.outcome shouldBe McpToolCallOutcome.FAILED
                    recorded.captured.errorCode shouldBe "IllegalStateException"
                }
            }
        }

        given("an audit repository that throws") {
            val auditRepository = mockk<McpToolCallHistoryRepository>()
            every { auditRepository.record(call = any()) } throws IllegalStateException("audit down")
            val gate = gateWith(role = UserRole.ADMIN, auditRepository = auditRepository)

            `when`("a tool is dispatched successfully") {
                val result =
                    gate.execute(
                        transportContext = contextWith(token = token),
                        toolName = "get_status",
                        requiredPermission = CommandPermission.OPERATIONS,
                    ) { "tool output" }

                then("the tool result still succeeds") {
                    result.isError shouldBe false
                    result.text() shouldBe "tool output"
                }
            }
        }

        given("a role resolver that throws") {
            val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = token.userId) } throws IllegalStateException("role lookup down")
            val gate =
                McpToolGate(
                    commandRoleResolver = roleResolver,
                    mcpToolCallHistoryRepository = auditRepository,
                )

            `when`("a tool is dispatched") {
                val result =
                    gate.execute(
                        transportContext = contextWith(token = token),
                        toolName = "get_status",
                        requiredPermission = CommandPermission.OPERATIONS,
                    ) { "must not run" }

                then("it fails closed as a tool error without running the body") {
                    result.isError shouldBe true
                    result.text() shouldContain "get_status"
                }

                then("a FAILED audit row records the floor role and the error code") {
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.outcome shouldBe McpToolCallOutcome.FAILED
                    recorded.captured.resolvedRole shouldBe UserRole.USER
                    recorded.captured.errorCode shouldBe "IllegalStateException"
                }
            }
        }
    })
