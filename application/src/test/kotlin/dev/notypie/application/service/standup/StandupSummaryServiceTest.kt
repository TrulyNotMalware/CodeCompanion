package dev.notypie.application.service.standup

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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

        given("postSummary") {
            `when`("a cutoff event is received for a collecting session") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val stager = mockk<OutboundMessageStager>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        stager = stager,
                        transactionManager = stubTransactionManager(),
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
                val slackEvent =
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.STANDUP_SUMMARY,
                        idempotencyKey = UUID.randomUUID(),
                    )
                every { repo.findSession(sessionUid = sessionUid) } returns session
                every { repo.getRoutine(routineUid = routineUid) } returns routine
                every {
                    stager.stage(
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
                } returns slackEvent
                every { outboxRepo.save(any<OutboxMessage>()) } answers { firstArg() }
                every {
                    repo.markSessionSummarized(
                        sessionId = 7L,
                        messageTs = "outbox:${slackEvent.payload.eventId}",
                    )
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

                then("a summary is staged to the channel, saved to the outbox and marked summarized") {
                    verify(exactly = 1) {
                        stager.stage(
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
                    verify(exactly = 1) { outboxRepo.save(any<OutboxMessage>()) }
                    verify(exactly = 1) {
                        repo.markSessionSummarized(
                            sessionId = 7L,
                            messageTs = "outbox:${slackEvent.payload.eventId}",
                        )
                    }
                }
            }

            `when`("the cutoff event references a session that no longer exists") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        stager = mockk(),
                        transactionManager = stubTransactionManager(),
                    )
                val sessionUid = UUID.randomUUID()
                every { repo.findSession(sessionUid = sessionUid) } returns null

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

            `when`("markSessionSummarized rejects the transition (already SUMMARIZED)") {
                val repo = mockk<StandupRepository>()
                val outboxRepo = mockk<MessageOutboxRepository>()
                val stager = mockk<OutboundMessageStager>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = outboxRepo,
                        stager = stager,
                        transactionManager = stubTransactionManager(),
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
                val slackEvent =
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.STANDUP_SUMMARY,
                        idempotencyKey = UUID.randomUUID(),
                    )
                every { repo.findSession(sessionUid = sessionUid) } returns session
                every { repo.getRoutine(routineUid = routineUid) } returns routine
                every {
                    stager.stage(message = any(), basicInfo = any())
                } returns slackEvent
                every { outboxRepo.save(any<OutboxMessage>()) } answers { firstArg() }
                // The transition lost — another tick already summarized this session.
                every {
                    repo.markSessionSummarized(
                        sessionId = 9L,
                        messageTs = "outbox:${slackEvent.payload.eventId}",
                    )
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

                then("the txn rolls back so neither the outbox row nor the marker survives") {
                    // outboxRepo.save is called inside the runCatching block; the rollback
                    // is enforced via TransactionStatus.setRollbackOnly() (stubbed in
                    // stubTransactionManager) so we only assert the CAS attempt itself.
                    verify(exactly = 1) {
                        repo.markSessionSummarized(
                            sessionId = 9L,
                            messageTs = "outbox:${slackEvent.payload.eventId}",
                        )
                    }
                }
            }

            `when`("the outbox relay reports a Slack message ts for the summary post") {
                val repo = mockk<StandupRepository>()
                val service =
                    StandupSummaryService(
                        standupRepository = repo,
                        outboxRepository = mockk(relaxed = true),
                        stager = mockk(relaxed = true),
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
