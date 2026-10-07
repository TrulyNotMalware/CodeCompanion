package dev.notypie.application.service.standup

import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createStandupOpsRequestEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StandupOpsAction
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

class StandupRoutineOpsServiceTest :
    BehaviorSpec({
        val creatorId = "U_CREATOR"
        val otherUserId = "U_OTHER"
        val routineName = "Daily Sync"

        fun serviceWith(
            standupRepository: StandupRepository,
            roleResolver: CommandRoleResolver,
            staged: CapturingSlot<OutboundMessage>,
        ): Pair<StandupRoutineOpsService, EventPublisher> {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(staged), basicInfo = any()) } returns
                mockk<CommandEvent<EventPayload>>(relaxed = true)
            val publisher = mockk<EventPublisher>(relaxed = true)
            val service =
                StandupRoutineOpsService(
                    standupRepository = standupRepository,
                    commandRoleResolver = roleResolver,
                    outboundStager = stager,
                    eventPublisher = publisher,
                )
            return service to publisher
        }

        fun CapturingSlot<OutboundMessage>.ephemeral(): OutboundMessage.Ephemeral =
            captured
                .shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                .also { message ->
                    message.target.id shouldBe TEST_CHANNEL_ID
                    message.recipient?.id shouldBe TEST_USER_ID
                }

        fun CapturingSlot<OutboundMessage>.markdown(): String =
            ephemeral()
                .content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown

        fun stopEvent(name: String = routineName) =
            createStandupOpsRequestEvent(action = StandupOpsAction.STOP, routineName = name)

        given("a LIST event in a channel with two active routines") {
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(
                    createRoutineDto(
                        name = routineName,
                        creatorId = creatorId,
                        summaryChannel = "C_SUMMARY",
                        triggerLocalTime = LocalTime.of(9, 30),
                        cutoffOffset = Duration.ofMinutes(90L),
                        weekdays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                        routineTimezone = ZoneId.of("Asia/Seoul"),
                        members =
                            listOf(
                                createRoutineMemberDto(userId = "U_ALICE"),
                                createRoutineMemberDto(userId = "U_BOB"),
                            ),
                    ),
                    createRoutineDto(
                        name = "Retro <team>",
                        members = listOf(createRoutineMemberDto(userId = "U_CAROL")),
                    ),
                )
            val staged = slot<OutboundMessage>()
            val (service, publisher) =
                serviceWith(
                    standupRepository = standupRepository,
                    roleResolver = mockk(),
                    staged = staged,
                )

            `when`("handled") {
                service.handleStandupOps(event = createStandupOpsRequestEvent(action = StandupOpsAction.LIST))

                then("the requester gets an ephemeral list of both routines with schedule and member counts") {
                    staged.ephemeral().detailType shouldBe CommandDetailType.STANDUP_ROUTINE_LIST
                    val markdown = staged.markdown()
                    markdown shouldContain "Active standup routines in <#$TEST_CHANNEL_ID> (2):"
                    markdown shouldContain
                        "• *Daily Sync* — 09:30 Asia/Seoul, Mon/Wed/Fri, cutoff +90m, 2 member(s), " +
                        "summary → <#C_SUMMARY>, created by <@$creatorId>"
                    markdown shouldContain "• *Retro &lt;team&gt;* — "
                    markdown shouldContain "1 member(s)"
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }

        given("a LIST event in a channel with no active routines") {
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                emptyList()
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    standupRepository = standupRepository,
                    roleResolver = mockk(),
                    staged = staged,
                )

            `when`("handled") {
                service.handleStandupOps(event = createStandupOpsRequestEvent(action = StandupOpsAction.LIST))

                then("the reply points at /standup setup") {
                    staged.markdown() shouldBe
                        "No active standup routines in this channel. Run `/standup setup` to create one."
                }
            }
        }

        given("a STOP event from the routine's creator") {
            val routine = createRoutineDto(name = routineName, creatorId = TEST_USER_ID)
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(routine)
            every { standupRepository.deactivateRoutine(routineUid = routine.routineUid) } returns true
            val roleResolver = mockk<CommandRoleResolver>()
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(standupRepository = standupRepository, roleResolver = roleResolver, staged = staged)

            `when`("handled with a differently-cased name") {
                service.handleStandupOps(event = stopEvent(name = "daily sync"))

                then("the routine is deactivated once without a role lookup and the requester is told") {
                    verify(exactly = 1) { standupRepository.deactivateRoutine(routineUid = routine.routineUid) }
                    verify(exactly = 0) { roleResolver.resolve(userId = any()) }
                    staged.ephemeral().detailType shouldBe CommandDetailType.STANDUP_ROUTINE_STOP
                    staged.markdown() shouldBe
                        "Stopped standup routine `Daily Sync`. No new sessions will open; prompts and nudges " +
                        "still pending for it are skipped, and a session that is already collecting will still " +
                        "be summarized at its cutoff."
                }
            }
        }

        given("a STOP event from an admin who did not create the routine") {
            val routine = createRoutineDto(name = routineName, creatorId = creatorId)
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(routine)
            every { standupRepository.deactivateRoutine(routineUid = routine.routineUid) } returns true
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = TEST_USER_ID) } returns UserRole.ADMIN
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(standupRepository = standupRepository, roleResolver = roleResolver, staged = staged)

            `when`("handled") {
                service.handleStandupOps(event = stopEvent())

                then("the admin may stop it") {
                    verify(exactly = 1) { standupRepository.deactivateRoutine(routineUid = routine.routineUid) }
                    staged.markdown() shouldContain "Stopped standup routine `Daily Sync`."
                }
            }
        }

        given("a STOP event from a plain user who did not create the routine") {
            val routine = createRoutineDto(name = routineName, creatorId = creatorId)
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(routine)
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = TEST_USER_ID) } returns UserRole.USER
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(standupRepository = standupRepository, roleResolver = roleResolver, staged = staged)

            `when`("handled") {
                service.handleStandupOps(event = stopEvent())

                then("the request is denied and the routine is left active") {
                    verify(exactly = 0) { standupRepository.deactivateRoutine(routineUid = any()) }
                    staged.markdown() shouldBe
                        "Only the routine creator (<@$creatorId>) or an admin can stop `Daily Sync`."
                }
            }
        }

        given("a STOP event naming no active routine in the channel") {
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(createRoutineDto(name = routineName, creatorId = TEST_USER_ID))
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(standupRepository = standupRepository, roleResolver = mockk(), staged = staged)

            `when`("handled with an unknown name containing markup") {
                service.handleStandupOps(event = stopEvent(name = "weekly <retro>"))

                then("the escaped name is echoed with a pointer to /standup list") {
                    verify(exactly = 0) { standupRepository.deactivateRoutine(routineUid = any()) }
                    staged.markdown() shouldBe
                        "No active standup routine named `weekly &lt;retro&gt;` in this channel. " +
                        "Run `/standup list` to see them."
                }
            }
        }

        given("a STOP event racing another stop") {
            val routine = createRoutineDto(name = routineName, creatorId = TEST_USER_ID)
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(routine)
            every { standupRepository.deactivateRoutine(routineUid = routine.routineUid) } returns false
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(standupRepository = standupRepository, roleResolver = mockk(), staged = staged)

            `when`("deactivateRoutine reports no row changed") {
                service.handleStandupOps(event = stopEvent())

                then("the requester is told it was already stopped") {
                    staged.markdown() shouldBe "`Daily Sync` was already stopped."
                }
            }
        }

        given("a STOP event whose basic info comes from another channel") {
            val standupRepository = mockk<StandupRepository>()
            every { standupRepository.findActiveRoutinesByChannel(commandChannel = "C_ELSEWHERE") } returns
                emptyList()
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(standupRepository = standupRepository, roleResolver = mockk(), staged = staged)

            `when`("handled") {
                service.handleStandupOps(
                    event =
                        createStandupOpsRequestEvent(
                            action = StandupOpsAction.STOP,
                            routineName = routineName,
                            responseBasicInfo = createCommandBasicInfo(channel = "C_ELSEWHERE"),
                        ),
                )

                then("only routines of the command's own channel are considered") {
                    verify(exactly = 1) {
                        standupRepository.findActiveRoutinesByChannel(commandChannel = "C_ELSEWHERE")
                    }
                    verify(exactly = 0) { standupRepository.deactivateRoutine(routineUid = any()) }
                    val reply = staged.captured.shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    reply.target.id shouldBe "C_ELSEWHERE"
                }
            }
        }
    })
