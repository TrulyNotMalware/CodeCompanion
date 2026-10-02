package dev.notypie.application.service.standup

import com.slack.api.model.block.SectionBlock
import dev.notypie.application.outbox.captureChains
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.service.meeting.createH2DataSource
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createStandupAnswerDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackBlockLimits
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.LocalDate
import java.util.UUID

class StandupSummaryServiceTest :
    BehaviorSpec({
        fun stubTransactionManager(): PlatformTransactionManager {
            val transactionManager = mockk<PlatformTransactionManager>()
            val status = mockk<TransactionStatus>(relaxed = true)
            every { transactionManager.getTransaction(any()) } returns status
            every { transactionManager.commit(any()) } just Runs
            every { transactionManager.rollback(any()) } just Runs
            return transactionManager
        }

        given("a cutoff for a full routine whose stored answers fill several Slack messages") {
            val questions = (1..8).map { index -> "질문$index " + "가".repeat(n = 194) }
            val members = (1..30).map { createRoutineMemberDto(userId = "U0123456789$it") }
            val sessionUid = UUID.randomUUID()
            val routineUid = UUID.randomUUID()
            val repo = mockk<StandupRepository>()
            val outboxRepo = mockk<MessageOutboxRepository>()
            val codec = CodecOutboundMessagePort()
            val port = mockk<OutboundMessagePort>()
            val chains = mutableListOf<List<OutboundMessage>>()
            port.captureChains(chains = chains) { message, basicInfo, continuation ->
                codec.toRow(message = message, basicInfo = basicInfo, continuation = continuation)
            }
            val saved = mutableListOf<OutboxMessage>()
            every { outboxRepo.save(capture(saved)) } answers { firstArg() }
            every { repo.findSessionForSummary(sessionUid = sessionUid) } returns
                createStandupSessionDto(
                    sessionId = 21L,
                    sessionUid = sessionUid,
                    routineUid = routineUid,
                    answers =
                        members.map { member ->
                            createStandupAnswerDto(
                                userId = member.userId,
                                responses = questions.map { "나".repeat(3_000) },
                            )
                        },
                )
            every { repo.getRoutine(routineUid = routineUid) } returns
                createRoutineDto(
                    routineUid = routineUid,
                    name = "Daily Standup",
                    members = members,
                    questions = questions,
                )
            every { repo.markSessionSummarized(sessionId = 21L, messageTs = any()) } returns true
            val templateBuilder = ModalTemplateBuilder(slackApiToken = "xoxb-test")

            `when`("the summary is posted") {
                StandupSummaryService(
                    standupRepository = repo,
                    outboxRepository = outboxRepo,
                    outboundMessagePort = port,
                    transactionManager = stubTransactionManager(),
                ).postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 21L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = LocalDate.of(2026, 5, 4),
                        ),
                )
                val summaries =
                    chains.flatten().map {
                        (it as OutboundMessage.ChannelMessage).content as MessageContent.StandupSummary
                    }

                then("it is split into several messages, each within Slack's total block text and block count") {
                    (summaries.size > 1) shouldBe true
                    summaries.forEach { summary ->
                        val blocks =
                            templateBuilder
                                .standupSummaryTemplate(
                                    routineName = summary.routineName,
                                    sessionDate = summary.sessionDate,
                                    members = summary.members,
                                    answers = summary.answers,
                                    questions = summary.questions,
                                ).template
                        val texts = blocks.map { it.shouldBeInstanceOf<SectionBlock>().text.text }
                        blocks.size shouldBeLessThanOrEqual SlackBlockLimits.MESSAGE_MAX_BLOCKS
                        texts.sumOf { it.length } shouldBeLessThanOrEqual SlackBlockLimits.MESSAGE_TEXT_BUDGET
                    }
                }

                then("every member appears once, in routine order, and each part is labelled") {
                    summaries.flatMap { summary -> summary.members.map { it.userId } } shouldBe
                        members.map { it.userId }
                    summaries.mapIndexed { index, summary ->
                        summary.routineName shouldBe "Daily Standup (${index + 1}/${summaries.size})"
                    }
                }

                then("only the first part is staged, carrying the rest in order behind it") {
                    chains.size shouldBe 1
                    saved.size shouldBe 1
                }

                then("the session is marked summarized once, with the first row's marker") {
                    verify(exactly = 1) {
                        repo.markSessionSummarized(sessionId = 21L, messageTs = "outbox:${saved.first().eventId}")
                    }
                }
            }
        }

        given("a cutoff for a session whose answers exceed what one member section shows") {
            val repo = mockk<StandupRepository>()
            val outboxRepo = mockk<MessageOutboxRepository>()
            val port = mockk<OutboundMessagePort>()
            val service =
                StandupSummaryService(
                    standupRepository = repo,
                    outboxRepository = outboxRepo,
                    outboundMessagePort = port,
                    transactionManager = stubTransactionManager(),
                )
            val sessionUid = UUID.randomUUID()
            val routineUid = UUID.randomUUID()
            val session =
                createStandupSessionDto(
                    sessionId = 11L,
                    sessionUid = sessionUid,
                    routineUid = routineUid,
                    answers = listOf(createStandupAnswerDto(userId = "U_LONG", responses = listOf("x".repeat(5_000)))),
                )
            val staged = slot<OutboundMessage>()
            every { repo.findSessionForSummary(sessionUid = sessionUid) } returns session
            every { repo.getRoutine(routineUid = routineUid) } returns
                createRoutineDto(routineUid = routineUid, members = listOf(createRoutineMemberDto(userId = "U_LONG")))
            every { port.toRow(message = capture(staged), basicInfo = any()) } returns
                createOutboxRow(eventId = "EVT-11")
            every { outboxRepo.save(any<OutboxMessage>()) } answers { firstArg() }
            every { repo.markSessionSummarized(sessionId = 11L, messageTs = "outbox:EVT-11") } returns true

            `when`("the summary is posted") {
                service.postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 11L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = session.sessionDate,
                        ),
                )

                then("the stored summary carries the member's answer bounded to one section, not the raw one") {
                    val content =
                        (staged.captured as OutboundMessage.ChannelMessage).content as MessageContent.StandupSummary
                    val response =
                        content.answers
                            .single()
                            .responses
                            .single()
                    response.length shouldBe SUMMARY_MEMBER_RESPONSE_CHARS
                    response.endsWith("…") shouldBe true
                }
            }
        }

        given("postSummary") {
            `when`("a cutoff event is received for a collecting session") {
                val dataSource = createH2DataSource()
                val jdbc = JdbcTemplate(dataSource)
                jdbc.execute("CREATE TABLE outbox_probe (event_id VARCHAR(64) PRIMARY KEY)")
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val port = mockk<OutboundMessagePort>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        outboundMessagePort = port,
                        transactionManager = createH2TransactionManager(dataSource = dataSource),
                    )
                val sessionUid = UUID.randomUUID()
                val routineUid = UUID.randomUUID()
                val sessionDate = LocalDate.of(2026, 5, 4)
                val session =
                    createStandupSessionDto(
                        sessionId = 7L,
                        sessionUid = sessionUid,
                        routineUid = routineUid,
                        sessionDate = sessionDate,
                    )
                val routine =
                    createRoutineDto(
                        routineUid = routineUid,
                        name = "Daily Standup",
                        summaryChannel = "C_SUMMARY",
                        questions = listOf("Yesterday?", "Today?"),
                        members = listOf(createRoutineMemberDto(userId = "U_STANDUP")),
                    )
                val summaryRow = createOutboxRow(eventId = "EVT-SUMMARY")
                every { repo.findSessionForSummary(sessionUid = sessionUid) } returns session
                every { repo.getRoutine(routineUid = routineUid) } returns routine
                every {
                    port.toRow(
                        message =
                            OutboundMessage.ChannelMessage(
                                target = ConversationTarget(id = "C_SUMMARY"),
                                content =
                                    MessageContent.StandupSummary(
                                        routineName = "Daily Standup",
                                        sessionDate = sessionDate,
                                        members = routine.members,
                                        answers = session.answers,
                                        questions = routine.questions,
                                    ),
                            ),
                        basicInfo = any(),
                    )
                } returns summaryRow
                every { outboxRepo.save(any<OutboxMessage>()) } answers {
                    jdbc.update("INSERT INTO outbox_probe (event_id) VALUES (?)", firstArg<OutboxMessage>().eventId)
                    firstArg()
                }
                every {
                    repo.markSessionSummarized(sessionId = 7L, messageTs = "outbox:EVT-SUMMARY")
                } returns true

                service.postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 7L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = sessionDate,
                        ),
                )

                then("a summary row is built to the channel, saved to the outbox and committed with the mark") {
                    jdbc.queryForObject("SELECT COUNT(*) FROM outbox_probe", Int::class.java) shouldBe 1
                    verify(exactly = 1) {
                        port.toRow(
                            message =
                                OutboundMessage.ChannelMessage(
                                    target = ConversationTarget(id = "C_SUMMARY"),
                                    content =
                                        MessageContent.StandupSummary(
                                            routineName = "Daily Standup",
                                            sessionDate = sessionDate,
                                            members = routine.members,
                                            answers = session.answers,
                                            questions = routine.questions,
                                        ),
                                ),
                            basicInfo = match { it.channel == "C_SUMMARY" },
                        )
                    }
                    verify(exactly = 1) { outboxRepo.save(summaryRow) }
                    verify(exactly = 1) {
                        repo.markSessionSummarized(sessionId = 7L, messageTs = "outbox:EVT-SUMMARY")
                    }
                    verify(exactly = 0) { repo.findSession(sessionUid = any()) }
                }
            }

            `when`("the locked read happens inside the transaction that saves the summary") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val transactionManager = stubTransactionManager()
                val port = mockk<OutboundMessagePort>()
                every { port.toRow(message = any(), basicInfo = any()) } returns createOutboxRow(eventId = "EVT-LOCK")
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        outboundMessagePort = port,
                        transactionManager = transactionManager,
                    )
                val sessionUid = UUID.randomUUID()
                val routineUid = UUID.randomUUID()
                every { repo.findSessionForSummary(sessionUid = sessionUid) } returns
                    createStandupSessionDto(sessionId = 12L, sessionUid = sessionUid, routineUid = routineUid)
                every { repo.getRoutine(routineUid = routineUid) } returns createRoutineDto(routineUid = routineUid)
                every { outboxRepo.save(any<OutboxMessage>()) } answers { firstArg() }
                every { repo.markSessionSummarized(sessionId = 12L, messageTs = "outbox:EVT-LOCK") } returns true

                service.postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 12L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = LocalDate.of(2026, 5, 4),
                        ),
                )

                then("the session is read after the transaction begins and before the save, CAS and commit") {
                    verifyOrder {
                        transactionManager.getTransaction(any())
                        repo.findSessionForSummary(sessionUid = sessionUid)
                        outboxRepo.save(any<OutboxMessage>())
                        repo.markSessionSummarized(sessionId = 12L, messageTs = "outbox:EVT-LOCK")
                        transactionManager.commit(any())
                    }
                }
            }

            `when`("the locked read finds the session already summarized by another replica") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val port = mockk<OutboundMessagePort>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        outboundMessagePort = port,
                        transactionManager = stubTransactionManager(),
                    )
                val sessionUid = UUID.randomUUID()
                every { repo.findSessionForSummary(sessionUid = sessionUid) } returns
                    createStandupSessionDto(
                        sessionId = 8L,
                        sessionUid = sessionUid,
                        status = SessionStatus.SUMMARIZED,
                        summaryMessageTs = "outbox:EVT-OTHER",
                    )

                service.postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 8L,
                            sessionUid = sessionUid,
                            routineUid = UUID.randomUUID(),
                            sessionDate = LocalDate.of(2026, 5, 4),
                        ),
                )

                then("nothing is built, saved or flipped") {
                    verify(exactly = 0) { repo.getRoutine(routineUid = any()) }
                    verify(exactly = 0) { port.toRow(message = any(), basicInfo = any()) }
                    verify(exactly = 0) { outboxRepo.save(any<OutboxMessage>()) }
                    verify(exactly = 0) { repo.markSessionSummarized(any(), any()) }
                }
            }

            `when`("the cutoff event references a session that no longer exists") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        outboundMessagePort = mockk(),
                        transactionManager = stubTransactionManager(),
                    )
                val sessionUid = UUID.randomUUID()
                every { repo.findSessionForSummary(sessionUid = sessionUid) } returns null

                service.postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 7L,
                            sessionUid = sessionUid,
                            routineUid = UUID.randomUUID(),
                            sessionDate = LocalDate.now(),
                        ),
                )

                then("no outbox row is enqueued and no CAS is attempted") {
                    verify(exactly = 0) { outboxRepo.save(any<OutboxMessage>()) }
                    verify(exactly = 0) { repo.markSessionSummarized(any(), any()) }
                }
            }

            `when`("markSessionSummarized rejects the transition (already SUMMARIZED) on a real transaction manager") {
                val dataSource = createH2DataSource()
                val jdbc = JdbcTemplate(dataSource)
                jdbc.execute("CREATE TABLE outbox_probe (event_id VARCHAR(64) PRIMARY KEY)")
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val port = mockk<OutboundMessagePort>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        outboundMessagePort = port,
                        transactionManager = createH2TransactionManager(dataSource = dataSource),
                    )
                val sessionUid = UUID.randomUUID()
                val routineUid = UUID.randomUUID()
                val sessionDate = LocalDate.of(2026, 5, 4)
                val session =
                    createStandupSessionDto(
                        sessionId = 9L,
                        sessionUid = sessionUid,
                        routineUid = routineUid,
                        sessionDate = sessionDate,
                    )
                val routine =
                    createRoutineDto(
                        routineUid = routineUid,
                        name = "Daily Standup",
                        summaryChannel = "C_SUMMARY",
                        questions = listOf("Yesterday?"),
                        members = listOf(createRoutineMemberDto(userId = "U_STANDUP")),
                    )
                every { repo.findSessionForSummary(sessionUid = sessionUid) } returns session
                every { repo.getRoutine(routineUid = routineUid) } returns routine
                every { port.toRow(message = any(), basicInfo = any()) } returns createOutboxRow(eventId = "EVT-9")
                every { outboxRepo.save(any<OutboxMessage>()) } answers {
                    jdbc.update("INSERT INTO outbox_probe (event_id) VALUES (?)", firstArg<OutboxMessage>().eventId)
                    firstArg()
                }
                every {
                    repo.markSessionSummarized(sessionId = 9L, messageTs = "outbox:EVT-9")
                } returns false

                service.postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 9L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = sessionDate,
                        ),
                )

                then("the txn rolls back so the summary row written before the rejected CAS does not survive") {
                    jdbc.queryForObject("SELECT COUNT(*) FROM outbox_probe", Int::class.java) shouldBe 0
                    verify(exactly = 1) { outboxRepo.save(any<OutboxMessage>()) }
                    verify(exactly = 1) {
                        repo.markSessionSummarized(sessionId = 9L, messageTs = "outbox:EVT-9")
                    }
                }
            }

            `when`("the outbox relay reports a Slack message ts for the summary post") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        outboundMessagePort = mockk(relaxed = true),
                        transactionManager = stubTransactionManager(),
                    )
                val eventId = UUID.randomUUID()
                every {
                    repo.replaceSummaryMessageTs(
                        currentMessageTs = "outbox:$eventId",
                        messageTs = "1700000000.000700",
                    )
                } returns true

                service.replaceSummaryMarkerWithSlackTs(
                    event =
                        MessagePublishSuccessEvent(
                            eventId = eventId,
                            messageTs = "1700000000.000700",
                        ),
                )

                then("the enqueue marker is replaced by Slack's chat.postMessage ts") {
                    verify(exactly = 1) {
                        repo.replaceSummaryMessageTs(
                            currentMessageTs = "outbox:$eventId",
                            messageTs = "1700000000.000700",
                        )
                    }
                }
            }
        }
    })
