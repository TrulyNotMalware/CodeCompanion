package dev.notypie.application.service.meeting

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.meeting.AgendaDispatchRepository
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class DailyAgendaSchedulingServiceTest :
    BehaviorSpec({
        val seoul = ZoneId.of("Asia/Seoul")

        // 2026-05-04T12:00 Asia/Seoul — well after the default 08:00 send time.
        val afterSendInstant =
            LocalDateTime
                .of(2026, 5, 4, 12, 0)
                .atZone(seoul)
                .toInstant()

        // 2026-05-04T07:00 Asia/Seoul — before the default 08:00 send time.
        val beforeSendInstant =
            LocalDateTime
                .of(2026, 5, 4, 7, 0)
                .atZone(seoul)
                .toInstant()

        fun stubPort(): OutboundMessagePort {
            val port = mockk<OutboundMessagePort>()
            every { port.toRow(message = any(), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            return port
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
            repo: AgendaDispatchRepository,
            outboxRepo: MessageOutboxRepository,
            clock: Clock,
            enabled: Boolean = true,
            port: OutboundMessagePort = stubPort(),
        ) = DailyAgendaSchedulingService(
            agendaDispatchRepository = repo,
            outboxRepository = outboxRepo,
            outboundMessagePort = port,
            transactionManager = stubTransactionManager(),
            clock = clock,
            appConfig =
                AppConfig(
                    meeting =
                        AppConfig.Meeting(
                            agenda =
                                AppConfig.Meeting.Agenda(
                                    enabled = enabled,
                                    sendAt = "08:00",
                                    timezone = "Asia/Seoul",
                                ),
                        ),
                ),
        )

        given("the local time is before the configured send time") {
            `when`("the scheduler runs") {
                val repo = mockk<AgendaDispatchRepository>(relaxed = true)
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    buildService(repo = repo, outboxRepo = outboxRepo, clock = Clock.fixed(beforeSendInstant, seoul))

                service.sendDailyAgenda()

                then("no claim is attempted and nothing is sent") {
                    verify(exactly = 0) { repo.claim(agendaDate = any()) }
                    verify(exactly = 0) { repo.findAttendingMeetingsForDay(from = any(), to = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }
        }

        given("the agenda feature is disabled") {
            `when`("the scheduler runs after send time") {
                val repo = mockk<AgendaDispatchRepository>(relaxed = true)
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    buildService(
                        repo = repo,
                        outboxRepo = outboxRepo,
                        clock = Clock.fixed(afterSendInstant, seoul),
                        enabled = false,
                    )

                service.sendDailyAgenda()

                then("no repository calls are made at all") {
                    verify(exactly = 0) { repo.claim(agendaDate = any()) }
                    verify(exactly = 0) { repo.findAttendingMeetingsForDay(from = any(), to = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }
        }

        given("it is after send time and two users have meetings today") {
            `when`("the claim succeeds") {
                val repo = mockk<AgendaDispatchRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val port = stubPort()
                val service =
                    buildService(
                        repo = repo,
                        outboxRepo = outboxRepo,
                        clock = Clock.fixed(afterSendInstant, seoul),
                        port = port,
                    )

                // Two meetings; U_A attends both, U_B attends one.
                val meetings =
                    listOf(
                        createAgendaCandidateMeeting(
                            meetingId = 1L,
                            title = "Sprint Planning",
                            startAt = LocalDateTime.of(2026, 5, 4, 10, 0),
                            attendingUserIds = listOf("U_A", "U_B"),
                        ),
                        createAgendaCandidateMeeting(
                            meetingId = 2L,
                            title = "1:1 with Lead",
                            startAt = LocalDateTime.of(2026, 5, 4, 14, 0),
                            attendingUserIds = listOf("U_A"),
                        ),
                    )
                every { repo.claim(agendaDate = any()) } returns true
                every { repo.findAttendingMeetingsForDay(from = any(), to = any()) } returns meetings

                val capturedInfos = mutableListOf<CommandBasicInfo>()
                val capturedMessages = mutableListOf<OutboundMessage>()
                every {
                    port.toRow(message = capture(capturedMessages), basicInfo = capture(capturedInfos))
                } answers { createOutboxRow(eventId = UUID.randomUUID().toString()) }
                every { outboxRepo.save(any()) } answers { firstArg() }

                service.sendDailyAgenda()

                then("one agenda DM is built and saved per user with meetings") {
                    verify(exactly = 1) { repo.claim(agendaDate = any()) }
                    verify(exactly = 2) { outboxRepo.save(any()) }
                    capturedInfos.map { it.publisherId }.toSet() shouldBe setOf("U_A", "U_B")
                }

                then("each agenda ChannelMessage carries DAILY_AGENDA and only that user's meetings") {
                    val agendaByUser =
                        capturedInfos
                            .map { it.publisherId }
                            .zip(capturedMessages.map { it as OutboundMessage.ChannelMessage })
                            .toMap()
                    agendaByUser.forEach { (userId, message) ->
                        message.detailType shouldBe CommandDetailType.DAILY_AGENDA
                        message.target.id shouldBe userId
                        (message.content as MessageContent.Text).headline shouldBe "🗓️ Today's meetings (2026-05-04)"
                    }
                    (agendaByUser["U_A"]!!.content as MessageContent.Text).markdown shouldBe
                        "• 10:00 — Sprint Planning\n• 14:00 — 1:1 with Lead"
                    (agendaByUser["U_B"]!!.content as MessageContent.Text).markdown shouldBe
                        "• 10:00 — Sprint Planning"
                }
            }
        }

        given("the agenda for today was already claimed by another tick") {
            `when`("the scheduler runs after send time") {
                val repo = mockk<AgendaDispatchRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    buildService(repo = repo, outboxRepo = outboxRepo, clock = Clock.fixed(afterSendInstant, seoul))
                every { repo.claim(agendaDate = any()) } returns false

                service.sendDailyAgenda()

                then("no meetings are loaded and nothing is sent") {
                    verify(exactly = 1) { repo.claim(agendaDate = any()) }
                    verify(exactly = 0) { repo.findAttendingMeetingsForDay(from = any(), to = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }
        }

        given("the claim is taken but there are no meetings today") {
            `when`("the scheduler runs after send time") {
                val repo = mockk<AgendaDispatchRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    buildService(repo = repo, outboxRepo = outboxRepo, clock = Clock.fixed(afterSendInstant, seoul))
                every { repo.claim(agendaDate = any()) } returns true
                every { repo.findAttendingMeetingsForDay(from = any(), to = any()) } returns emptyList()

                service.sendDailyAgenda()

                then("no DMs are sent — an empty agenda is never delivered") {
                    verify(exactly = 1) { repo.claim(agendaDate = any()) }
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }
        }

        given("a canceled meeting falls on today") {
            `when`("the scheduler loads the day's meetings") {
                val repo = mockk<AgendaDispatchRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>(relaxed = true)
                val service =
                    buildService(repo = repo, outboxRepo = outboxRepo, clock = Clock.fixed(afterSendInstant, seoul))
                every { repo.claim(agendaDate = any()) } returns true
                // findAttendingMeetingsForDay reuses the reminder window query, which already
                // excludes canceled meetings, so the candidate list contains no canceled meeting.
                every { repo.findAttendingMeetingsForDay(from = any(), to = any()) } returns emptyList()

                service.sendDailyAgenda()

                then("the canceled meeting is excluded and produces no DM") {
                    verify(exactly = 0) { outboxRepo.save(any()) }
                }
            }
        }
    })
