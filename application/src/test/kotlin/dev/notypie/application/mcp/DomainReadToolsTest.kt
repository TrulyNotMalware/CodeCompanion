package dev.notypie.application.mcp

import dev.notypie.application.security.mcp.SCOPED_TURN_TOKEN_CONTEXT_KEY
import dev.notypie.application.security.mcp.createScopedTurnToken
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private fun CallToolResult.text(): String = content().first().shouldBeInstanceOf<TextContent>().text()

class DomainReadToolsTest :
    BehaviorSpec({
        val token = createScopedTurnToken()
        val fixedClock = Clock.fixed(Instant.parse("2026-07-08T03:00:00Z"), ZoneOffset.UTC)
        val now = LocalDateTime.now(fixedClock)

        val requestContext = mockk<McpSyncRequestContext>()
        every { requestContext.transportContext() } returns
            McpTransportContext.create(mapOf(SCOPED_TURN_TOKEN_CONTEXT_KEY to token))

        fun toolsWith(
            role: UserRole,
            opsStatusService: OpsStatusService = mockk(),
            roleManagementService: RoleManagementService = mockk(),
            meetingRepository: MeetingRepository = mockk(),
        ): DomainReadTools {
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = token.userId) } returns role
            return DomainReadTools(
                mcpToolGate =
                    McpToolGate(
                        commandRoleResolver = roleResolver,
                        mcpToolCallHistoryRepository = mockk<McpToolCallHistoryRepository>(relaxed = true),
                    ),
                opsStatusService = opsStatusService,
                roleManagementService = roleManagementService,
                meetingRepository = meetingRepository,
                clock = fixedClock,
            )
        }

        given("get_status") {
            val opsStatusService = mockk<OpsStatusService>()
            every { opsStatusService.renderReport() } returns "status-report"

            `when`("called by a developer") {
                val result =
                    toolsWith(role = UserRole.DEVELOPER, opsStatusService = opsStatusService)
                        .getStatus(context = requestContext)

                then("the ops report is returned verbatim") {
                    result.isError shouldBe false
                    result.text() shouldBe "status-report"
                }
            }

            `when`("called by a plain user") {
                val result =
                    toolsWith(role = UserRole.USER, opsStatusService = opsStatusService)
                        .getStatus(context = requestContext)

                then("dispatch is denied by the gate") {
                    result.isError shouldBe true
                    result.text() shouldContain "permission"
                }
            }
        }

        given("list_meetings") {
            `when`("called without an explicit window") {
                val meetingRepository = mockk<MeetingRepository>()
                every {
                    meetingRepository.getMeetingsByUserIdInRange(
                        userId = token.userId,
                        startAt = now,
                        endAt = now.plusDays(7L),
                    )
                } returns
                    listOf(
                        createMeetingDto(title = "Weekly sync", startAt = now.plusDays(1L)),
                        createMeetingDto(title = "Cancelled retro", startAt = now.plusDays(2L), isCanceled = true),
                    )
                val result =
                    toolsWith(role = UserRole.USER, meetingRepository = meetingRepository)
                        .listMeetings(daysAhead = null, context = requestContext)

                then("the token's user is queried over the default 7-day window") {
                    result.isError shouldBe false
                    result.text() shouldContain "Weekly sync"
                }

                then("cancelled meetings are filtered out") {
                    result.text() shouldNotContain "Cancelled retro"
                }
            }

            `when`("called with an out-of-range window") {
                val meetingRepository = mockk<MeetingRepository>()
                every {
                    meetingRepository.getMeetingsByUserIdInRange(
                        userId = token.userId,
                        startAt = now,
                        endAt = now.plusDays(31L),
                    )
                } returns emptyList()
                val result =
                    toolsWith(role = UserRole.USER, meetingRepository = meetingRepository)
                        .listMeetings(daysAhead = 99, context = requestContext)

                then("the window is clamped to 31 days and an empty listing is reported") {
                    result.isError shouldBe false
                    result.text() shouldContain "No meetings"
                }
            }
        }

        given("list_roles") {
            val roleManagementService = mockk<RoleManagementService>()
            every { roleManagementService.renderGrants() } returns "grant-listing"

            `when`("called by an admin") {
                val result =
                    toolsWith(role = UserRole.ADMIN, roleManagementService = roleManagementService)
                        .listRoles(context = requestContext)

                then("the grant listing is returned verbatim") {
                    result.isError shouldBe false
                    result.text() shouldBe "grant-listing"
                }
            }

            `when`("called by a developer") {
                val result =
                    toolsWith(role = UserRole.DEVELOPER, roleManagementService = roleManagementService)
                        .listRoles(context = requestContext)

                then("dispatch is denied by the gate") {
                    result.isError shouldBe true
                }
            }
        }
    })
