package dev.notypie.application.service.standup

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createSessionDispatchDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.standup.ReadyDispatch
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class StandupSchedulingServiceTest :
    BehaviorSpec({
        val seoul = ZoneId.of("Asia/Seoul")
        val la = ZoneId.of("America/Los_Angeles")

        // 2026-05-04 Monday in Asia/Seoul (= 2026-05-03T15:00:00Z UTC).
        val nowInstant =
            LocalDate
                .of(2026, 5, 4)
                .atTime(LocalTime.NOON)
                .atZone(seoul)
                .toInstant()
        val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)
        val today = LocalDate.ofInstant(nowInstant, seoul)

        fun stubEventBuilder(): SlackApiEventConstructor {
            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val basicInfo = createCommandBasicInfo()
            val stubEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.STANDUP_PROMPT,
                    idempotencyKey = basicInfo.idempotencyKey,
                )
            every {
                slackEventBuilder.simpleApplyRejectRequest(
                    commandDetailType = any(),
                    commandBasicInfo = any(),
                    approvalContents = any(),
                    targetUserId = any(),
                    routingExtras = any(),
                )
            } returns stubEvent
            every {
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = any(),
                    headLineText = any(),
                    commandBasicInfo = any(),
                    simpleString = any(),
                )
            } returns stubEvent
            return slackEventBuilder
        }

        fun stubTransactionManager(): PlatformTransactionManager {
            val tm = mockk<PlatformTransactionManager>()
            val status = mockk<TransactionStatus>(relaxed = true)
            every { tm.getTransaction(any()) } returns status
            every { tm.commit(any()) } just Runs
            every { tm.rollback(any()) } just Runs
            return tm
        }

        given("openSessionsForToday") {
            `when`("today is a configured weekday and no session exists yet") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val routine =
                    createRoutineDto(
                        triggerLocalTime = LocalTime.of(10, 0),
                        cutoffOffset = Duration.ofHours(1L),
                        weekdays = setOf(DayOfWeek.MONDAY),
                        routineTimezone = seoul,
                        members =
                            listOf(
                                createRoutineMemberDto(userId = "U_A", userTimezone = seoul),
                                createRoutineMemberDto(userId = "U_B", userTimezone = la),
                            ),
                    )

                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.findSession(routineUid = routine.routineUid, sessionDate = today) } returns null
                val createdSession = slot<StandupSession>()
                every { repo.createSession(session = capture(createdSession)) } answers { firstArg() }

                service.openSessionsForToday()

                then("creates session with cutoffAt anchored at the LATEST member trigger + offset") {
                    val session = createdSession.captured
                    session.routineUid shouldBe routine.routineUid
                    session.sessionDate shouldBe today
                    // Latest member trigger = LA 10:00 (UTC 17:00 in DST). + 1h cutoff offset = LA 11:00.
                    // Anchoring on max(member trigger) guarantees every member has at least
                    // cutoffOffset to respond after their DM fires.
                    session.cutoffAt shouldBe
                        LocalDate
                            .of(2026, 5, 4)
                            .atTime(11, 0)
                            .atZone(la)
                            .toInstant()

                    val byUser = session.dispatchSnapshot().associateBy { it.userId }
                    byUser["U_A"]!!.dmTriggerAt shouldBe
                        LocalDate
                            .of(2026, 5, 4)
                            .atTime(10, 0)
                            .atZone(seoul)
                            .toInstant()
                    byUser["U_B"]!!.dmTriggerAt shouldBe
                        LocalDate
                            .of(2026, 5, 4)
                            .atTime(10, 0)
                            .atZone(la)
                            .toInstant()
                }
            }

            `when`("today is NOT in the routine's weekday set") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                every { repo.listActiveRoutines() } returns
                    listOf(
                        createRoutineDto(
                            weekdays = setOf(DayOfWeek.SATURDAY),
                            routineTimezone = seoul,
                            members = listOf(createRoutineMemberDto(userId = "U_A", userTimezone = seoul)),
                        ),
                    )

                service.openSessionsForToday()

                then("findSession is never consulted and createSession is never called") {
                    verify(exactly = 0) { repo.findSession(routineUid = any(), sessionDate = any()) }
                    verify(exactly = 0) { repo.createSession(session = any()) }
                }
            }

            `when`("a session already exists for today") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val routine =
                    createRoutineDto(
                        weekdays = setOf(DayOfWeek.MONDAY),
                        routineTimezone = seoul,
                    )
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.findSession(routineUid = routine.routineUid, sessionDate = today) } returns
                    mockk(relaxed = true)

                service.openSessionsForToday()

                then("createSession is skipped — idempotent across ticks") {
                    verify(exactly = 0) { repo.createSession(session = any()) }
                }
            }

            `when`("createSession throws DataIntegrityViolationException AND a session exists (concurrent race)") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val routine =
                    createRoutineDto(
                        weekdays = setOf(DayOfWeek.MONDAY),
                        routineTimezone = seoul,
                    )
                every { repo.listActiveRoutines() } returns listOf(routine)
                // First lookup (pre-create check): null. Second lookup (post-DIE confirm): exists.
                every { repo.findSession(routineUid = routine.routineUid, sessionDate = today) } returnsMany
                    listOf(null, mockk(relaxed = true))
                every { repo.createSession(session = any()) } throws
                    DataIntegrityViolationException("uk_standup_session_routine_date violated")

                service.openSessionsForToday()

                then("the exception is swallowed because the racing row is confirmed to exist") {
                    verify(exactly = 1) { repo.createSession(session = any()) }
                    verify(exactly = 2) { repo.findSession(routineUid = routine.routineUid, sessionDate = today) }
                }
            }

            `when`("createSession throws DataIntegrityViolationException but NO session exists (real bug)") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val routine =
                    createRoutineDto(
                        weekdays = setOf(DayOfWeek.MONDAY),
                        routineTimezone = seoul,
                    )
                every { repo.listActiveRoutines() } returns listOf(routine)
                // Both lookups return null — the violation was NOT the expected unique-constraint race.
                every { repo.findSession(routineUid = routine.routineUid, sessionDate = today) } returns null
                every { repo.createSession(session = any()) } throws
                    DataIntegrityViolationException("UUID collision on session_uid")

                then("the exception propagates so the underlying schema/data bug is not hidden") {
                    try {
                        service.openSessionsForToday()
                        throw AssertionError("expected DataIntegrityViolationException to propagate")
                    } catch (ex: DataIntegrityViolationException) {
                        ex.message?.contains("UUID collision") shouldBe true
                    }
                }
            }

            `when`("createSession throws an unexpected runtime error (not a constraint violation)") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val routine =
                    createRoutineDto(
                        weekdays = setOf(DayOfWeek.MONDAY),
                        routineTimezone = seoul,
                    )
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.findSession(routineUid = routine.routineUid, sessionDate = today) } returns null
                every { repo.createSession(session = any()) } throws RuntimeException("DB outage")

                then("the exception propagates so it can be caught by the scheduler tick wrapper") {
                    try {
                        service.openSessionsForToday()
                        throw AssertionError("expected RuntimeException to propagate")
                    } catch (ex: RuntimeException) {
                        ex.message shouldBe "DB outage"
                    }
                }
            }
        }

        given("sendPendingDispatches") {
            val routineUid = UUID.randomUUID()
            val routine =
                createRoutineDto(
                    routineUid = routineUid,
                    name = "Daily Standup",
                    weekdays = setOf(DayOfWeek.MONDAY),
                    routineTimezone = seoul,
                    members = listOf(createRoutineMemberDto(userId = "U_A", userTimezone = seoul)),
                )

            fun readyDispatchOf(dispatchId: Long, userId: String, triggerOffsetSeconds: Long): ReadyDispatch =
                ReadyDispatch(
                    dispatch =
                        createSessionDispatchDto(
                            id = dispatchId,
                            userId = userId,
                            dmTriggerAt = nowInstant.plusSeconds(triggerOffsetSeconds),
                        ),
                    sessionUid = UUID.randomUUID(),
                    sessionDate = today,
                    cutoffAt = nowInstant.plusSeconds(3600L),
                    sessionStatus = SessionStatus.COLLECTING,
                    summaryMessageTs = null,
                    routineUid = routineUid,
                )

            `when`("a dispatch is ready and claim succeeds") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val builder = stubEventBuilder()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = builder,
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val ready = readyDispatchOf(dispatchId = 42L, userId = "U_A", triggerOffsetSeconds = -60L)

                val claimedToken = slot<String>()
                val sentToken = slot<String>()
                every { repo.resetStuckDispatches(olderThan = any()) } returns 0
                every { repo.findPendingDispatchesBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.claimDispatch(dispatchId = 42L, claimToken = capture(claimedToken)) } returns true
                every {
                    repo.markDispatchSent(dispatchId = 42L, claimToken = capture(sentToken), sentAt = any())
                } returns true
                val savedOutbox = slot<OutboxMessage>()
                every { outboxRepo.save(capture(savedOutbox)) } answers { firstArg() }

                service.sendPendingDispatches()

                then("dispatch is claimed, outbox row persisted, markSent called") {
                    verify(exactly = 1) { repo.claimDispatch(dispatchId = 42L, claimToken = any()) }
                    verify(exactly = 1) { outboxRepo.save(any()) }
                    verify(exactly = 1) { repo.markDispatchSent(dispatchId = 42L, claimToken = any(), sentAt = any()) }
                    verify(
                        exactly = 0,
                    ) { repo.markDispatchFailed(dispatchId = any(), claimToken = any(), reason = any()) }
                }

                then("the same claim token is threaded from claim through markDispatchSent") {
                    // Without token threading, a stuck-row recovery + re-claim by another tick
                    // would let our markDispatchSent silently flip B's claim to SENT. The token
                    // CAS predicate is what makes that safe.
                    sentToken.captured shouldBe claimedToken.captured
                }

                then("the outbox row carries STANDUP_FILL and the member's user_id as channel") {
                    savedOutbox.captured.commandDetailType shouldBe CommandDetailType.STANDUP_PROMPT.wireValue
                }
            }

            `when`("the claim fails (race lost)") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val builder = stubEventBuilder()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = builder,
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val ready = readyDispatchOf(dispatchId = 42L, userId = "U_A", triggerOffsetSeconds = -60L)

                every { repo.resetStuckDispatches(olderThan = any()) } returns 0
                every { repo.findPendingDispatchesBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.claimDispatch(dispatchId = 42L, claimToken = any()) } returns false

                service.sendPendingDispatches()

                then("no outbox save, no markSent, no markFailed") {
                    verify(exactly = 0) { outboxRepo.save(any()) }
                    verify(
                        exactly = 0,
                    ) { repo.markDispatchSent(dispatchId = any(), claimToken = any(), sentAt = any()) }
                    verify(
                        exactly = 0,
                    ) { repo.markDispatchFailed(dispatchId = any(), claimToken = any(), reason = any()) }
                }
            }

            `when`("the message build throws") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val builder = mockk<SlackApiEventConstructor>()
                every {
                    builder.simpleApplyRejectRequest(
                        commandDetailType = any(),
                        commandBasicInfo = any(),
                        approvalContents = any(),
                        targetUserId = any(),
                        routingExtras = any(),
                    )
                } throws RuntimeException("Slack API error")
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = builder,
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val ready = readyDispatchOf(dispatchId = 42L, userId = "U_A", triggerOffsetSeconds = -60L)

                every { repo.resetStuckDispatches(olderThan = any()) } returns 0
                every { repo.findPendingDispatchesBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.claimDispatch(dispatchId = 42L, claimToken = any()) } returns true
                every { repo.markDispatchFailed(dispatchId = 42L, claimToken = any(), reason = any()) } returns true

                service.sendPendingDispatches()

                then("dispatch is marked FAILED with the exception message") {
                    verify(exactly = 1) {
                        repo.markDispatchFailed(
                            dispatchId = 42L,
                            claimToken = any(),
                            reason = match { it.contains("Slack API error") },
                        )
                    }
                    verify(
                        exactly = 0,
                    ) { repo.markDispatchSent(dispatchId = any(), claimToken = any(), sentAt = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("nothing is pending") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                every { repo.resetStuckDispatches(olderThan = any()) } returns 0
                every { repo.findPendingDispatchesBefore(before = any(), limit = any()) } returns emptyList()

                service.sendPendingDispatches()

                then("no work is done downstream") {
                    verify(exactly = 0) { repo.listActiveRoutines() }
                    verify(exactly = 0) { repo.claimDispatch(dispatchId = any(), claimToken = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("a ready dispatch points at an inactive/unknown routine") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val ready = readyDispatchOf(dispatchId = 99L, userId = "U_A", triggerOffsetSeconds = -60L)

                every { repo.resetStuckDispatches(olderThan = any()) } returns 0
                every { repo.findPendingDispatchesBefore(before = any(), limit = any()) } returns listOf(ready)
                // listActiveRoutines returns empty — the routine for this dispatch is missing
                every { repo.listActiveRoutines() } returns emptyList()

                service.sendPendingDispatches()

                then("the dispatch is skipped, no claim attempted") {
                    verify(exactly = 0) { repo.claimDispatch(dispatchId = any(), claimToken = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }
        }

        given("detectCutoffs") {
            `when`("a COLLECTING session is past its cutoff") {
                val repo = mockk<StandupRepository>()
                val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        applicationEventPublisher = publisher,
                        clock = clock,
                    )
                val sessionUid = UUID.randomUUID()
                val routineUid = UUID.randomUUID()
                every { repo.findCollectingSessionsPastCutoff(before = any()) } returns
                    listOf(
                        createStandupSessionDto(
                            sessionId = 9L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = LocalDate.of(2026, 5, 4),
                        ),
                    )

                service.detectCutoffs()

                then("a StandupCutoffEvent is published for the summary service") {
                    verify(exactly = 1) { repo.findCollectingSessionsPastCutoff(before = any()) }
                    verify(exactly = 1) {
                        publisher.publishEvent(
                            StandupCutoffEvent(
                                sessionId = 9L,
                                sessionUid = sessionUid,
                                routineUid = routineUid,
                                sessionDate = LocalDate.of(2026, 5, 4),
                            ),
                        )
                    }
                }
            }
        }

        given("nudgeNonResponders") {
            val routineUid = UUID.randomUUID()
            val routine =
                createRoutineDto(
                    routineUid = routineUid,
                    name = "Daily Standup",
                    routineTimezone = seoul,
                )

            `when`("a session in the nudge window has two non-responders") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                // U_A and U_C received the prompt (SENT); only U_C answered → U_A + a third sent
                // member who never answered are the non-responders.
                val candidate =
                    createNudgeCandidateSession(
                        sessionId = 7L,
                        routineUid = routineUid,
                        cutoffAt = nowInstant.plusSeconds(600L),
                        sentMemberIds = setOf("U_A", "U_B", "U_C"),
                        answeredUserIds = setOf("U_C"),
                    )

                every {
                    repo.findCollectingSessionsForNudge(now = any(), nudgeWindowEnd = any())
                } returns listOf(candidate)
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.claimNudge(sessionId = 7L) } returns true
                every { outboxRepo.save(any()) } answers { firstArg() }

                service.nudgeNonResponders()

                then("the nudge is claimed once and one outbox row is saved per non-responder") {
                    verify(exactly = 1) { repo.claimNudge(sessionId = 7L) }
                    // sent − answered = {U_A, U_B} → exactly two reminder DMs.
                    verify(exactly = 2) { outboxRepo.save(any()) }
                }
            }

            `when`("every member who received the prompt has answered") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val candidate =
                    createNudgeCandidateSession(
                        sessionId = 8L,
                        routineUid = routineUid,
                        cutoffAt = nowInstant.plusSeconds(600L),
                        sentMemberIds = setOf("U_A", "U_B"),
                        answeredUserIds = setOf("U_A", "U_B"),
                    )

                every {
                    repo.findCollectingSessionsForNudge(now = any(), nudgeWindowEnd = any())
                } returns listOf(candidate)
                every { repo.listActiveRoutines() } returns listOf(routine)

                service.nudgeNonResponders()

                then("the nudge is NOT claimed and no DM is sent — nudged_at stays NULL") {
                    verify(exactly = 0) { repo.claimNudge(sessionId = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("the claim is lost (already nudged by another tick)") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                    )
                val candidate =
                    createNudgeCandidateSession(
                        sessionId = 9L,
                        routineUid = routineUid,
                        cutoffAt = nowInstant.plusSeconds(600L),
                        sentMemberIds = setOf("U_A"),
                        answeredUserIds = emptySet(),
                    )

                every {
                    repo.findCollectingSessionsForNudge(now = any(), nudgeWindowEnd = any())
                } returns listOf(candidate)
                every { repo.listActiveRoutines() } returns listOf(routine)
                every { repo.claimNudge(sessionId = 9L) } returns false

                service.nudgeNonResponders()

                then("no DM is sent — the winning tick owns the reminder") {
                    verify(exactly = 1) { repo.claimNudge(sessionId = 9L) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("the nudge offset is disabled (<= 0)") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                        appConfig =
                            AppConfig(standup = AppConfig.Standup(nudge = AppConfig.Standup.Nudge(offsetMinutes = 0L))),
                    )

                service.nudgeNonResponders()

                then("the phase short-circuits with zero repository work") {
                    verify(exactly = 0) { repo.findCollectingSessionsForNudge(now = any(), nudgeWindowEnd = any()) }
                    verify(exactly = 0) { repo.claimNudge(sessionId = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("the selection window is computed from now + nudgeOffset") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    StandupSchedulingService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        slackEventBuilder = stubEventBuilder(),
                        transactionManager = stubTransactionManager(),
                        clock = clock,
                        appConfig =
                            AppConfig(
                                standup = AppConfig.Standup(nudge = AppConfig.Standup.Nudge(offsetMinutes = 30L)),
                            ),
                    )
                val passedNow = slot<java.time.Instant>()
                val passedWindowEnd = slot<java.time.Instant>()
                every {
                    repo.findCollectingSessionsForNudge(
                        now = capture(passedNow),
                        nudgeWindowEnd = capture(passedWindowEnd),
                    )
                } returns emptyList()

                service.nudgeNonResponders()

                then("the window is [now, now + 30m] so a cutoff beyond it is excluded by the query") {
                    passedNow.captured shouldBe nowInstant
                    passedWindowEnd.captured shouldBe nowInstant.plus(Duration.ofMinutes(30L))
                }
            }
        }
    })
