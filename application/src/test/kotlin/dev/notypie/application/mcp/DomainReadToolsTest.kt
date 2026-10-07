package dev.notypie.application.mcp

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.security.mcp.SCOPED_TURN_TOKEN_CONTEXT_KEY
import dev.notypie.application.security.mcp.createScopedTurnToken
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.command.RoleResolution
import dev.notypie.application.service.cve.query.CveLatestQueryService
import dev.notypie.application.service.cve.subscription.CveSubscriptionService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.mcp.McpToolCallRecord
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
            standupRepository: StandupRepository = mockk(),
            cveSubscriptionService: CveSubscriptionService = mockk(),
            cveLatestQueryService: CveLatestQueryService = mockk(),
            appConfig: AppConfig = AppConfig(),
            auditRepository: McpToolCallHistoryRepository = mockk(relaxed = true),
        ): DomainReadTools {
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolution(userId = token.userId) } returns
                RoleResolution(role = role, lookupFailed = false)
            return DomainReadTools(
                mcpToolGate =
                    McpToolGate(
                        commandRoleResolver = roleResolver,
                        mcpToolCallHistoryRepository = auditRepository,
                    ),
                opsStatusService = opsStatusService,
                roleManagementService = roleManagementService,
                meetingRepository = meetingRepository,
                standupRepository = standupRepository,
                cveSubscriptionService = cveSubscriptionService,
                cveLatestQueryService = cveLatestQueryService,
                appConfig = appConfig,
                clock = fixedClock,
            )
        }

        val cveEnabled = AppConfig(cve = AppConfig.Cve(enabled = true))
        val cveDisabled = AppConfig(cve = AppConfig.Cve(enabled = false))

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

            `when`("a meeting title carries Slack control sequences") {
                val meetingRepository = mockk<MeetingRepository>()
                every {
                    meetingRepository.getMeetingsByUserIdInRange(userId = any(), startAt = any(), endAt = any())
                } returns
                    listOf(
                        createMeetingDto(title = "<!channel> <https://evil.example|Sync>", startAt = now.plusDays(1L)),
                    )
                val result =
                    toolsWith(role = UserRole.USER, meetingRepository = meetingRepository)
                        .listMeetings(daysAhead = null, context = requestContext)

                then("the title reaches the model escaped, so an echoed title stays literal text in Slack") {
                    result.text() shouldContain "• &lt;!channel&gt; &lt;https://evil.example|Sync&gt; — "
                    result.text() shouldNotContain "<!channel>"
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

        given("list_standups") {
            `when`("the requester is a member of one routine and outside another") {
                val standupRepository = mockk<StandupRepository>()
                every { standupRepository.listActiveRoutines() } returns
                    listOf(
                        createRoutineDto(
                            name = "Backend sync",
                            commandChannel = "C_BACKEND",
                            summaryChannel = "C_SUMMARY",
                            members =
                                listOf(
                                    createRoutineMemberDto(userId = token.userId),
                                    createRoutineMemberDto(userId = "U_OTHER"),
                                ),
                        ),
                        createRoutineDto(
                            name = "Design sync",
                            members = listOf(createRoutineMemberDto(userId = "U_OTHER")),
                        ),
                    )
                val result =
                    toolsWith(role = UserRole.USER, standupRepository = standupRepository)
                        .listStandups(context = requestContext)

                then("only the routine the requester belongs to is rendered, with channel and member count") {
                    result.isError shouldBe false
                    result.text() shouldBe
                        "• Backend sync — 10:00 Asia/Seoul, Mon/Tue/Wed/Thu/Fri, cutoff +60m, 2 member(s), " +
                        "channel <#C_BACKEND>, summary <#C_SUMMARY>, created by <@$TEST_USER_ID>"
                }
            }

            `when`("the requester created a routine without being a member") {
                val standupRepository = mockk<StandupRepository>()
                every { standupRepository.listActiveRoutines() } returns
                    listOf(createRoutineDto(name = "Owner sync", creatorId = token.userId))
                val result =
                    toolsWith(role = UserRole.USER, standupRepository = standupRepository)
                        .listStandups(context = requestContext)

                then("the creator still sees the routine") {
                    result.text() shouldContain "• Owner sync — "
                    result.text() shouldContain "0 member(s)"
                }
            }

            `when`("there is no active routine") {
                val standupRepository = mockk<StandupRepository>()
                every { standupRepository.listActiveRoutines() } returns emptyList()
                val result =
                    toolsWith(role = UserRole.USER, standupRepository = standupRepository)
                        .listStandups(context = requestContext)

                then("the empty text is returned") {
                    result.isError shouldBe false
                    result.text() shouldBe "You are not in any active standup routine."
                }
            }

            `when`("a routine name carries Slack control sequences") {
                val standupRepository = mockk<StandupRepository>()
                every { standupRepository.listActiveRoutines() } returns
                    listOf(
                        createRoutineDto(
                            name = "<https://evil|x>",
                            members = listOf(createRoutineMemberDto(userId = token.userId)),
                        ),
                    )
                val result =
                    toolsWith(role = UserRole.USER, standupRepository = standupRepository)
                        .listStandups(context = requestContext)

                then("the name reaches the model escaped, so an echoed name stays literal text in Slack") {
                    result.text() shouldContain "• &lt;https://evil|x&gt; — "
                    result.text() shouldNotContain "<https://evil|x>"
                }
            }
        }

        given("list_cve_subscriptions") {
            `when`("the CVE feature is disabled") {
                val cveSubscriptionService = mockk<CveSubscriptionService>()
                val result =
                    toolsWith(
                        role = UserRole.USER,
                        cveSubscriptionService = cveSubscriptionService,
                        appConfig = cveDisabled,
                    ).listCveSubscriptions(context = requestContext)

                then("the disabled text is returned and the service is never called") {
                    result.isError shouldBe false
                    result.text() shouldBe "The CVE feature is currently disabled."
                    verify(exactly = 0) { cveSubscriptionService.renderSubscriptions(userId = any()) }
                }
            }

            `when`("the CVE feature is enabled") {
                val cveSubscriptionService = mockk<CveSubscriptionService>()
                every { cveSubscriptionService.renderSubscriptions(userId = token.userId) } returns "subscriptions"
                val result =
                    toolsWith(
                        role = UserRole.USER,
                        cveSubscriptionService = cveSubscriptionService,
                        appConfig = cveEnabled,
                    ).listCveSubscriptions(context = requestContext)

                then("the token's user is delegated and the text is returned verbatim") {
                    result.isError shouldBe false
                    result.text() shouldBe "subscriptions"
                    verify(exactly = 1) { cveSubscriptionService.renderSubscriptions(userId = token.userId) }
                }
            }
        }

        given("cve_latest") {
            `when`("the CVE feature is enabled and a topic key is given") {
                val cveLatestQueryService = mockk<CveLatestQueryService>()
                every { cveLatestQueryService.renderLatest(userId = token.userId, topicKey = "kotlin") } returns
                    "latest-kotlin"
                val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
                val result =
                    toolsWith(
                        role = UserRole.USER,
                        cveLatestQueryService = cveLatestQueryService,
                        appConfig = cveEnabled,
                        auditRepository = auditRepository,
                    ).cveLatest(topicKey = "kotlin", context = requestContext)

                then("the key is delegated and audited") {
                    result.isError shouldBe false
                    result.text() shouldBe "latest-kotlin"
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.argumentsJson shouldBe """{"topicKey":"kotlin"}"""
                }
            }

            `when`("the CVE feature is enabled and no topic key is given") {
                val cveLatestQueryService = mockk<CveLatestQueryService>()
                every { cveLatestQueryService.renderLatest(userId = token.userId, topicKey = null) } returns
                    "latest-subscribed"
                val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
                val result =
                    toolsWith(
                        role = UserRole.USER,
                        cveLatestQueryService = cveLatestQueryService,
                        appConfig = cveEnabled,
                        auditRepository = auditRepository,
                    ).cveLatest(topicKey = null, context = requestContext)

                then("the requester's subscriptions are used and a null key is audited") {
                    result.isError shouldBe false
                    result.text() shouldBe "latest-subscribed"
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.argumentsJson shouldBe """{"topicKey":null}"""
                }
            }

            `when`("the model sends a blank topic key") {
                val cveLatestQueryService = mockk<CveLatestQueryService>()
                every { cveLatestQueryService.renderLatest(userId = token.userId, topicKey = null) } returns
                    "latest-subscribed"
                val result =
                    toolsWith(
                        role = UserRole.USER,
                        cveLatestQueryService = cveLatestQueryService,
                        appConfig = cveEnabled,
                    ).cveLatest(topicKey = "  ", context = requestContext)

                then("it is treated as omitted") {
                    result.text() shouldBe "latest-subscribed"
                }
            }

            `when`("the topic key contains a double quote") {
                val cveLatestQueryService = mockk<CveLatestQueryService>()
                every { cveLatestQueryService.renderLatest(userId = token.userId, topicKey = "a\"b") } returns
                    "Topic not available"
                val auditRepository = mockk<McpToolCallHistoryRepository>(relaxed = true)
                toolsWith(
                    role = UserRole.USER,
                    cveLatestQueryService = cveLatestQueryService,
                    appConfig = cveEnabled,
                    auditRepository = auditRepository,
                ).cveLatest(topicKey = "a\"b", context = requestContext)

                then("the raw key is delegated and the audited summary stays valid JSON") {
                    verify(exactly = 1) { cveLatestQueryService.renderLatest(userId = token.userId, topicKey = "a\"b") }
                    val recorded = slot<McpToolCallRecord>()
                    verify(exactly = 1) { auditRepository.record(call = capture(recorded)) }
                    recorded.captured.argumentsJson shouldBe """{"topicKey":"a'b"}"""
                }
            }

            `when`("the CVE feature is disabled") {
                val cveLatestQueryService = mockk<CveLatestQueryService>()
                val result =
                    toolsWith(
                        role = UserRole.USER,
                        cveLatestQueryService = cveLatestQueryService,
                        appConfig = cveDisabled,
                    ).cveLatest(topicKey = "kotlin", context = requestContext)

                then("the disabled text is returned and the service is never called") {
                    result.isError shouldBe false
                    result.text() shouldBe "The CVE feature is currently disabled."
                    verify(exactly = 0) { cveLatestQueryService.renderLatest(userId = any(), topicKey = any()) }
                }
            }
        }
    })
