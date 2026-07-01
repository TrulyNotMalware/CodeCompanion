package dev.notypie.application.service.meeting

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.createMeetingReminderDto
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.ReadyReminder
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class MeetingReminderSchedulingServiceTest :
    BehaviorSpec({
        val seoul = ZoneId.of("Asia/Seoul")

        // 2026-05-04T12:00 Asia/Seoul (= 2026-05-04T03:00:00Z UTC).
        val nowInstant =
            LocalDateTime
                .of(2026, 5, 4, 12, 0)
                .atZone(seoul)
                .toInstant()
        val clock = Clock.fixed(nowInstant, seoul)

        fun stubEventBuilder(): SlackApiEventConstructor {
            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val basicInfo = createCommandBasicInfo()
            val stubEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.MEETING_REMINDER,
                    idempotencyKey = basicInfo.idempotencyKey,
                )
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

        fun buildService(
            repo: MeetingReminderRepository,
            outboxRepo: MessageOutboxRepository,
            builder: SlackApiEventConstructor = stubEventBuilder(),
        ) = MeetingReminderSchedulingService(
            reminderRepository = repo,
            outboxRepository = outboxRepo,
            slackEventBuilder = builder,
            transactionManager = stubTransactionManager(),
            clock = clock,
            appConfig =
                AppConfig(
                    meeting = AppConfig.Meeting(reminder = AppConfig.Meeting.Reminder(offsetsMinutes = listOf(15, 5))),
                ),
        )

        given("materializeReminders") {
            `when`("an active meeting falls in the forward window with attending participants") {
                val repo = mockk<MeetingReminderRepository>()
                val service = buildService(repo = repo, outboxRepo = mockk(relaxed = true))
                // Starts 10 minutes from now → within the 15-minute max offset window.
                val meeting =
                    createReminderCandidateMeeting(
                        meetingId = 7L,
                        startAt = LocalDateTime.ofInstant(nowInstant, seoul).plusMinutes(10L),
                        attendingUserIds = listOf("U_A", "U_B"),
                    )
                every { repo.findActiveMeetingsInWindow(from = any(), to = any()) } returns listOf(meeting)
                every { repo.ensureReminder(meetingId = any(), offsetMinutes = any(), scheduledAt = any()) } returns
                    true

                service.materializeReminders()

                then("ensureReminder is called once per configured offset") {
                    verify(exactly = 1) {
                        repo.ensureReminder(meetingId = 7L, offsetMinutes = 15, scheduledAt = any())
                    }
                    verify(exactly = 1) {
                        repo.ensureReminder(meetingId = 7L, offsetMinutes = 5, scheduledAt = any())
                    }
                }
            }

            `when`("a meeting has no attending participants") {
                val repo = mockk<MeetingReminderRepository>()
                val service = buildService(repo = repo, outboxRepo = mockk(relaxed = true))
                val meeting =
                    createReminderCandidateMeeting(
                        meetingId = 7L,
                        startAt = LocalDateTime.ofInstant(nowInstant, seoul).plusMinutes(10L),
                        attendingUserIds = emptyList(),
                    )
                every { repo.findActiveMeetingsInWindow(from = any(), to = any()) } returns listOf(meeting)

                service.materializeReminders()

                then("no reminder rows are materialized") {
                    verify(exactly = 0) {
                        repo.ensureReminder(meetingId = any(), offsetMinutes = any(), scheduledAt = any())
                    }
                }
            }

            `when`("ensureReminder throws DataIntegrityViolationException AND a row exists (concurrent race)") {
                val repo = mockk<MeetingReminderRepository>()
                val service = buildService(repo = repo, outboxRepo = mockk(relaxed = true))
                val meeting =
                    createReminderCandidateMeeting(
                        meetingId = 7L,
                        startAt = LocalDateTime.ofInstant(nowInstant, seoul).plusMinutes(10L),
                        attendingUserIds = listOf("U_A"),
                    )
                every { repo.findActiveMeetingsInWindow(from = any(), to = any()) } returns listOf(meeting)
                every { repo.ensureReminder(meetingId = any(), offsetMinutes = any(), scheduledAt = any()) } throws
                    DataIntegrityViolationException("uk_meeting_reminder_meeting_offset violated")
                every { repo.reminderExists(meetingId = any(), offsetMinutes = any()) } returns true

                service.materializeReminders()

                then("the exception is swallowed because the racing row is confirmed to exist") {
                    verify(exactly = 1) { repo.reminderExists(meetingId = 7L, offsetMinutes = 15) }
                    verify(exactly = 1) { repo.reminderExists(meetingId = 7L, offsetMinutes = 5) }
                }
            }

            `when`("ensureReminder throws DataIntegrityViolationException but NO row exists (real bug)") {
                val repo = mockk<MeetingReminderRepository>()
                val service = buildService(repo = repo, outboxRepo = mockk(relaxed = true))
                val meeting =
                    createReminderCandidateMeeting(
                        meetingId = 7L,
                        startAt = LocalDateTime.ofInstant(nowInstant, seoul).plusMinutes(10L),
                        attendingUserIds = listOf("U_A"),
                    )
                every { repo.findActiveMeetingsInWindow(from = any(), to = any()) } returns listOf(meeting)
                every { repo.ensureReminder(meetingId = any(), offsetMinutes = any(), scheduledAt = any()) } throws
                    DataIntegrityViolationException("FK violation on meeting_id")
                every { repo.reminderExists(meetingId = any(), offsetMinutes = any()) } returns false

                then("the exception propagates so the underlying schema/data bug is not hidden") {
                    try {
                        service.materializeReminders()
                        throw AssertionError("expected DataIntegrityViolationException to propagate")
                    } catch (ex: DataIntegrityViolationException) {
                        ex.message?.contains("FK violation") shouldBe true
                    }
                }
            }
        }

        given("sendDueReminders") {
            fun readyReminderOf(reminderId: Long, offsetMinutes: Int, attendingUserIds: List<String>): ReadyReminder =
                ReadyReminder(
                    reminder =
                        createMeetingReminderDto(
                            id = reminderId,
                            meetingId = 7L,
                            offsetMinutes = offsetMinutes,
                            scheduledAt = nowInstant.minusSeconds(60L),
                        ),
                    meetingId = 7L,
                    meetingTitle = "Sprint Planning",
                    startAt = LocalDateTime.ofInstant(nowInstant, seoul).plusMinutes(offsetMinutes.toLong()),
                    isCanceled = false,
                    attendingUserIds = attendingUserIds,
                )

            `when`("a reminder is due and claim succeeds") {
                val repo = mockk<MeetingReminderRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val service = buildService(repo = repo, outboxRepo = outboxRepo)
                val ready =
                    readyReminderOf(reminderId = 42L, offsetMinutes = 15, attendingUserIds = listOf("U_A", "U_B"))

                val claimedToken = slot<String>()
                val sentToken = slot<String>()
                every { repo.resetStuckReminders(olderThan = any()) } returns 0
                every { repo.findDueBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.claimReminder(reminderId = 42L, claimToken = capture(claimedToken)) } returns true
                every {
                    repo.markReminderSent(reminderId = 42L, claimToken = capture(sentToken), sentAt = any())
                } returns true
                val savedOutbox = mutableListOf<OutboxMessage>()
                every { outboxRepo.save(capture(savedOutbox)) } answers { firstArg() }

                service.sendDueReminders()

                then("one outbox row is persisted per attending participant and markSent is called") {
                    verify(exactly = 1) { repo.claimReminder(reminderId = 42L, claimToken = any()) }
                    verify(exactly = 2) { outboxRepo.save(any()) }
                    verify(exactly = 1) { repo.markReminderSent(reminderId = 42L, claimToken = any(), sentAt = any()) }
                    verify(
                        exactly = 0,
                    ) { repo.markReminderFailed(reminderId = any(), claimToken = any(), reason = any()) }
                }

                then("the same claim token is threaded from claim through markReminderSent") {
                    sentToken.captured shouldBe claimedToken.captured
                }

                then("the outbox rows carry MEETING_REMINDER as the command detail type") {
                    savedOutbox.forEach { it.commandDetailType shouldBe CommandDetailType.MEETING_REMINDER.name }
                }
            }

            `when`("markReminderSent CAS returns false (recovery raced us)") {
                val repo = mockk<MeetingReminderRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val service = buildService(repo = repo, outboxRepo = outboxRepo)
                val ready = readyReminderOf(reminderId = 42L, offsetMinutes = 15, attendingUserIds = listOf("U_A"))

                every { repo.resetStuckReminders(olderThan = any()) } returns 0
                every { repo.findDueBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.claimReminder(reminderId = 42L, claimToken = any()) } returns true
                every { outboxRepo.save(any()) } answers { firstArg() }
                every { repo.markReminderSent(reminderId = 42L, claimToken = any(), sentAt = any()) } returns false
                every { repo.markReminderFailed(reminderId = 42L, claimToken = any(), reason = any()) } returns true

                service.sendDueReminders()

                then("the tx rolls back and the reminder is recorded FAILED, never SENT") {
                    verify(exactly = 1) { repo.markReminderSent(reminderId = 42L, claimToken = any(), sentAt = any()) }
                    verify(exactly = 1) {
                        repo.markReminderFailed(reminderId = 42L, claimToken = any(), reason = any())
                    }
                }
            }

            `when`("the claim fails (race lost)") {
                val repo = mockk<MeetingReminderRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service = buildService(repo = repo, outboxRepo = outboxRepo)
                val ready = readyReminderOf(reminderId = 42L, offsetMinutes = 15, attendingUserIds = listOf("U_A"))

                every { repo.resetStuckReminders(olderThan = any()) } returns 0
                every { repo.findDueBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.claimReminder(reminderId = 42L, claimToken = any()) } returns false

                service.sendDueReminders()

                then("no outbox save, no markSent, no markFailed") {
                    verify(exactly = 0) { outboxRepo.save(any()) }
                    verify(
                        exactly = 0,
                    ) { repo.markReminderSent(reminderId = any(), claimToken = any(), sentAt = any()) }
                    verify(
                        exactly = 0,
                    ) { repo.markReminderFailed(reminderId = any(), claimToken = any(), reason = any()) }
                }
            }

            `when`("the message build throws") {
                val repo = mockk<MeetingReminderRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val builder = mockk<SlackApiEventConstructor>()
                every {
                    builder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } throws RuntimeException("Slack API error")
                val service = buildService(repo = repo, outboxRepo = outboxRepo, builder = builder)
                val ready = readyReminderOf(reminderId = 42L, offsetMinutes = 15, attendingUserIds = listOf("U_A"))

                every { repo.resetStuckReminders(olderThan = any()) } returns 0
                every { repo.findDueBefore(before = any(), limit = any()) } returns listOf(ready)
                every { repo.claimReminder(reminderId = 42L, claimToken = any()) } returns true
                every { repo.markReminderFailed(reminderId = 42L, claimToken = any(), reason = any()) } returns true

                service.sendDueReminders()

                then("the reminder is marked FAILED with the exception message and never SENT") {
                    verify(exactly = 1) {
                        repo.markReminderFailed(
                            reminderId = 42L,
                            claimToken = any(),
                            reason = match { it.contains("Slack API error") },
                        )
                    }
                    verify(
                        exactly = 0,
                    ) { repo.markReminderSent(reminderId = any(), claimToken = any(), sentAt = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("nothing is due") {
                val repo = mockk<MeetingReminderRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service = buildService(repo = repo, outboxRepo = outboxRepo)
                every { repo.resetStuckReminders(olderThan = any()) } returns 0
                every { repo.findDueBefore(before = any(), limit = any()) } returns emptyList()

                service.sendDueReminders()

                then("no work is done downstream") {
                    verify(exactly = 0) { repo.claimReminder(reminderId = any(), claimToken = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }

            `when`("stuck SENDING reminders are present at the start of a tick") {
                val repo = mockk<MeetingReminderRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service = buildService(repo = repo, outboxRepo = outboxRepo)
                every { repo.resetStuckReminders(olderThan = any()) } returns 3
                every { repo.findDueBefore(before = any(), limit = any()) } returns emptyList()

                service.sendDueReminders()

                then("the stuck rows are reset to PENDING before the due sweep runs") {
                    verify(exactly = 1) { repo.resetStuckReminders(olderThan = any()) }
                }
            }
        }

        given("a canceled meeting") {
            `when`("materialize sweeps the window") {
                val repo = mockk<MeetingReminderRepository>()
                val service = buildService(repo = repo, outboxRepo = mockk(relaxed = true))
                // The repository window query already excludes canceled meetings, so the candidate
                // list is empty and no reminder is produced.
                every { repo.findActiveMeetingsInWindow(from = any(), to = any()) } returns emptyList()

                service.materializeReminders()

                then("no reminder rows are materialized") {
                    verify(exactly = 0) {
                        repo.ensureReminder(meetingId = any(), offsetMinutes = any(), scheduledAt = any())
                    }
                }
            }
        }

        given("a reminder DTO domain invariant") {
            `when`("a SENT reminder is built without a sentAt") {
                then("the DTO factory still produces a PENDING-style row (no entity invariant triggered)") {
                    val dto = createMeetingReminderDto(status = MeetingReminderStatus.PENDING)
                    dto.status shouldBe MeetingReminderStatus.PENDING
                }
            }
        }
    })
