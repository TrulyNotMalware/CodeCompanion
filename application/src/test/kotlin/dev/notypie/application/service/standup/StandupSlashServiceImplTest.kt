package dev.notypie.application.service.standup

import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.slash.StandupCommand
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createSessionDispatchDto
import dev.notypie.domain.standup.createStandupAnswerDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.domain.standup.dto.StandupSessionDto
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.util.LinkedMultiValueMap
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StandupSlashServiceImplTest :
    BehaviorSpec({
        val seoul = ZoneId.of("Asia/Seoul")
        val utc = ZoneId.of("UTC")
        val mondayMorningInSeoul = createFixedUtcClock(now = LocalDateTime.of(2026, 5, 4, 1, 0))
        val sunday = LocalDate.of(2026, 5, 3)
        val monday = LocalDate.of(2026, 5, 4)
        val tuesday = LocalDate.of(2026, 5, 5)
        val mondayCutoff = Instant.parse("2026-05-04T03:00:00Z")
        val membership = listOf(createRoutineMemberDto(userId = TEST_USER_ID, userTimezone = seoul))

        val utcRoutine =
            createRoutineDto(
                routineTimezone = utc,
                members = listOf(createRoutineMemberDto(userId = TEST_USER_ID, userTimezone = utc)),
            )
        val mondayPrompt = Instant.parse("2026-05-04T10:00:00Z")
        val tuesdayPrompt = Instant.parse("2026-05-05T10:00:00Z")
        val mondayOvernightCutoff = Instant.parse("2026-05-05T10:00:00Z")
        val tuesdayOvernightCutoff = Instant.parse("2026-05-06T10:00:00Z")
        val actorAnswer = createStandupAnswerDto(userId = TEST_USER_ID)

        fun utcSession(
            sessionDate: LocalDate,
            cutoffAt: Instant,
            promptAt: Instant,
            answers: List<StandupAnswerDto> = emptyList(),
        ): StandupSessionDto =
            createStandupSessionDto(
                routineUid = utcRoutine.routineUid,
                sessionDate = sessionDate,
                cutoffAt = cutoffAt,
                dispatches = listOf(createSessionDispatchDto(userId = TEST_USER_ID, dmTriggerAt = promptAt)),
                answers = answers,
            )

        fun repositoryWith(routine: RoutineDto, sessionsByDate: Map<LocalDate, StandupSessionDto?>): StandupRepository {
            val repository = mockk<StandupRepository>()
            every { repository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns listOf(routine)
            sessionsByDate.forEach { (date, session) ->
                every { repository.findSession(routineUid = routine.routineUid, sessionDate = date) } returns session
            }
            return repository
        }

        fun runStandupSlash(
            repository: StandupRepository,
            clock: Clock,
            subCommands: List<String>,
        ): List<CommandEffect> {
            val commandExecutor = mockk<CommandExecutor>()
            val executed = slot<Command<StandupSubCommandDefinition>>()
            every { commandExecutor.execute(command = capture(executed)) } returns mockk(relaxed = true)
            StandupSlashServiceImpl(
                commandExecutor = commandExecutor,
                standupRepository = repository,
                clock = clock,
            ).handleStandup(
                headers = LinkedMultiValueMap(),
                payload = mockk(relaxed = true),
                commandData = createSlashInboundCommand(subCommands = subCommands, triggerId = "trigger-standup"),
            )
            val command = executed.captured.shouldBeInstanceOf<StandupCommand>()
            command.handleEvent()
            return command.drainIntents()
        }

        fun answerAt(repository: StandupRepository, now: LocalDateTime): List<CommandEffect> =
            runStandupSlash(
                repository = repository,
                clock = createFixedUtcClock(now = now),
                subCommands = listOf("answer"),
            )

        fun List<CommandEffect>.openedAnswerForm(): ModalForm.StandupFill =
            single()
                .shouldBeInstanceOf<OutboundMessage.OpenModal>()
                .form
                .shouldBeInstanceOf<ModalForm.StandupFill>()

        given("a /standup sub-command other than answer") {
            val repository = mockk<StandupRepository>()

            `when`("the service handles '/standup list'") {
                val effects =
                    runStandupSlash(repository = repository, clock = mondayMorningInSeoul, subCommands = listOf("list"))

                then("no candidate is preloaded, so the repository is never touched") {
                    verify { repository wasNot Called }
                    effects.single() shouldBe CommandIntent.ListStandupRoutines
                }
            }
        }

        given("/standup answer from a member whose routine is collecting today") {
            val routine = createRoutineDto(routineTimezone = seoul, members = membership)
            val session =
                createStandupSessionDto(routineUid = routine.routineUid, sessionDate = monday, cutoffAt = mondayCutoff)
            val repository =
                repositoryWith(routine = routine, sessionsByDate = mapOf(sunday to null, monday to session))

            `when`("the service handles it") {
                val effects =
                    runStandupSlash(
                        repository = repository,
                        clock = mondayMorningInSeoul,
                        subCommands = listOf("answer"),
                    )

                then("that session becomes the candidate and its answer modal opens") {
                    val form = effects.openedAnswerForm()
                    form.sessionUid shouldBe session.sessionUid
                    form.routineUid shouldBe routine.routineUid
                    form.requesterId shouldBe TEST_USER_ID
                }
            }
        }

        given("/standup answer in a channel where other routines do not qualify") {
            val collecting = createRoutineDto(name = "Collecting", routineTimezone = seoul, members = membership)
            val notAMember =
                createRoutineDto(
                    name = "Not A Member",
                    routineTimezone = seoul,
                    members = listOf(createRoutineMemberDto(userId = "U_SOMEONE_ELSE")),
                )
            val summarized = createRoutineDto(name = "Summarized", routineTimezone = seoul, members = membership)
            val noSessionYet = createRoutineDto(name = "No Session Yet", routineTimezone = seoul, members = membership)
            val collectingSession =
                createStandupSessionDto(
                    routineUid = collecting.routineUid,
                    sessionDate = monday,
                    cutoffAt = mondayCutoff,
                )
            val repository = mockk<StandupRepository>()
            every { repository.findActiveRoutinesByChannel(commandChannel = TEST_CHANNEL_ID) } returns
                listOf(notAMember, summarized, collecting, noSessionYet)
            every { repository.findSession(routineUid = any(), sessionDate = any()) } returns null
            every { repository.findSession(routineUid = collecting.routineUid, sessionDate = monday) } returns
                collectingSession
            every { repository.findSession(routineUid = summarized.routineUid, sessionDate = monday) } returns
                createStandupSessionDto(
                    routineUid = summarized.routineUid,
                    sessionDate = monday,
                    cutoffAt = mondayCutoff,
                    status = SessionStatus.SUMMARIZED,
                )
            every { repository.findSession(routineUid = notAMember.routineUid, sessionDate = monday) } returns
                createStandupSessionDto(
                    routineUid = notAMember.routineUid,
                    sessionDate = monday,
                    cutoffAt = mondayCutoff,
                )

            `when`("the service handles it without a routine name") {
                val effects =
                    runStandupSlash(
                        repository = repository,
                        clock = mondayMorningInSeoul,
                        subCommands = listOf("answer"),
                    )

                then("only the collecting routine is a candidate, so its modal opens instead of an ambiguity reply") {
                    effects.openedAnswerForm().sessionUid shouldBe collectingSession.sessionUid
                }

                then("a routine the requester is not a member of is not even looked up") {
                    verify(exactly = 0) {
                        repository.findSession(routineUid = notAMember.routineUid, sessionDate = any())
                    }
                }
            }
        }

        given("/standup answer from a member west of the routine zone after the routine's next day began") {
            val losAngeles = ZoneId.of("America/Los_Angeles")
            val routine =
                createRoutineDto(
                    routineTimezone = seoul,
                    members = listOf(createRoutineMemberDto(userId = TEST_USER_ID, userTimezone = losAngeles)),
                )
            val mondaySession =
                createStandupSessionDto(
                    routineUid = routine.routineUid,
                    sessionDate = monday,
                    cutoffAt = Instant.parse("2026-05-04T19:00:00Z"),
                )
            val tuesdaySession =
                createStandupSessionDto(
                    routineUid = routine.routineUid,
                    sessionDate = tuesday,
                    cutoffAt = Instant.parse("2026-05-05T19:00:00Z"),
                )
            val repository =
                repositoryWith(
                    routine = routine,
                    sessionsByDate = mapOf(sunday to null, monday to mondaySession, tuesday to tuesdaySession),
                )

            `when`("the service handles it at 02:30 on Tuesday in Seoul, still 10:30 on Monday in Los Angeles") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 4, 17, 30))

                then("the member's Monday session is the candidate and the routine zone's Tuesday is never read") {
                    effects.openedAnswerForm().sessionUid shouldBe mondaySession.sessionUid
                    verify(exactly = 0) {
                        repository.findSession(routineUid = routine.routineUid, sessionDate = tuesday)
                    }
                }
            }
        }

        given("/standup answer when today's session still says COLLECTING but its cutoff has arrived") {
            val routine = createRoutineDto(routineTimezone = seoul, members = membership)
            val pastCutoff =
                createStandupSessionDto(
                    routineUid = routine.routineUid,
                    sessionDate = monday,
                    cutoffAt = mondayMorningInSeoul.instant(),
                )
            val repository =
                repositoryWith(routine = routine, sessionsByDate = mapOf(sunday to null, monday to pastCutoff))

            `when`("the service handles it at the cutoff instant, before the sweep summarizes the session") {
                val effects =
                    runStandupSlash(
                        repository = repository,
                        clock = mondayMorningInSeoul,
                        subCommands = listOf("answer"),
                    )

                then("the session is not offered, so no form opens that the repository would refuse") {
                    effects
                        .single()
                        .shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe "No standup is collecting your answer in this channel right now."
                }
            }
        }

        given("a 24-hour cutoff keeps Monday's session open on Tuesday morning, before Tuesday's prompt") {
            val mondaySession =
                utcSession(sessionDate = monday, cutoffAt = mondayOvernightCutoff, promptAt = mondayPrompt)
            val tuesdaySession =
                utcSession(sessionDate = tuesday, cutoffAt = tuesdayOvernightCutoff, promptAt = tuesdayPrompt)
            val repository =
                repositoryWith(
                    routine = utcRoutine,
                    sessionsByDate = mapOf(monday to mondaySession, tuesday to tuesdaySession),
                )

            `when`("the member answers at 09:00 on Tuesday with Monday's prompt fired and unanswered") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 5, 9, 0))

                then("Monday's session is the candidate, not the member-date Tuesday session") {
                    effects.openedAnswerForm().sessionUid shouldBe mondaySession.sessionUid
                }
            }
        }

        given("Monday's session is answered and still open while Tuesday's prompt has fired") {
            val mondaySession =
                utcSession(
                    sessionDate = monday,
                    cutoffAt = Instant.parse("2026-05-05T11:00:00Z"),
                    promptAt = mondayPrompt,
                    answers = listOf(actorAnswer),
                )
            val tuesdaySession =
                utcSession(sessionDate = tuesday, cutoffAt = tuesdayOvernightCutoff, promptAt = tuesdayPrompt)
            val repository =
                repositoryWith(
                    routine = utcRoutine,
                    sessionsByDate = mapOf(monday to mondaySession, tuesday to tuesdaySession),
                )

            `when`("the member answers at 10:30 on Tuesday, before a later member's DM closes Monday at 11:00") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 5, 10, 30))

                then("Tuesday's fired and unanswered prompt wins over the earlier Monday session") {
                    effects.openedAnswerForm().sessionUid shouldBe tuesdaySession.sessionUid
                }
            }
        }

        given("Monday's session is answered and Tuesday's prompt has not fired yet") {
            val mondaySession =
                utcSession(
                    sessionDate = monday,
                    cutoffAt = mondayOvernightCutoff,
                    promptAt = mondayPrompt,
                    answers = listOf(actorAnswer),
                )
            val tuesdaySession =
                utcSession(sessionDate = tuesday, cutoffAt = tuesdayOvernightCutoff, promptAt = tuesdayPrompt)
            val repository =
                repositoryWith(
                    routine = utcRoutine,
                    sessionsByDate = mapOf(monday to mondaySession, tuesday to tuesdaySession),
                )

            `when`("the member answers at 09:30 on Tuesday") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 5, 9, 30))

                then("the unanswered Tuesday session wins over the earlier, answered Monday session") {
                    effects.openedAnswerForm().sessionUid shouldBe tuesdaySession.sessionUid
                }
            }
        }

        given("both open sessions are already answered by the member") {
            val mondaySession =
                utcSession(
                    sessionDate = monday,
                    cutoffAt = mondayOvernightCutoff,
                    promptAt = mondayPrompt,
                    answers = listOf(actorAnswer),
                )
            val tuesdaySession =
                utcSession(
                    sessionDate = tuesday,
                    cutoffAt = tuesdayOvernightCutoff,
                    promptAt = tuesdayPrompt,
                    answers = listOf(actorAnswer),
                )
            val repository =
                repositoryWith(
                    routine = utcRoutine,
                    sessionsByDate = mapOf(monday to mondaySession, tuesday to tuesdaySession),
                )

            `when`("the member answers at 09:30 on Tuesday") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 5, 9, 30))

                then("the earlier session date decides, so the choice stays deterministic") {
                    effects.openedAnswerForm().sessionUid shouldBe mondaySession.sessionUid
                }
            }
        }

        given("a Monday-only routine whose Monday session runs past midnight") {
            val mondaySession =
                utcSession(sessionDate = monday, cutoffAt = mondayOvernightCutoff, promptAt = mondayPrompt)
            val repository =
                repositoryWith(routine = utcRoutine, sessionsByDate = mapOf(monday to mondaySession, tuesday to null))

            `when`("the member answers at 09:00 on Tuesday, when no Tuesday session exists") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 5, 9, 0))

                then("Monday's session is still offered instead of nothing collecting") {
                    effects.openedAnswerForm().sessionUid shouldBe mondaySession.sessionUid
                }
            }
        }

        given("the only open session has not prompted the member yet") {
            val mondaySession =
                utcSession(
                    sessionDate = monday,
                    cutoffAt = Instant.parse("2026-05-04T11:00:00Z"),
                    promptAt = mondayPrompt,
                )
            val repository =
                repositoryWith(routine = utcRoutine, sessionsByDate = mapOf(sunday to null, monday to mondaySession))

            `when`("the member answers at 08:00 on Monday, two hours before the prompt") {
                val effects = answerAt(repository = repository, now = LocalDateTime.of(2026, 5, 4, 8, 0))

                then("the session is offered anyway, so a member can answer early") {
                    effects.openedAnswerForm().sessionUid shouldBe mondaySession.sessionUid
                }
            }
        }
    })
