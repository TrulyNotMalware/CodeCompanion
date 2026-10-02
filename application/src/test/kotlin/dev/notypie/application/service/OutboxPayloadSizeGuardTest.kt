package dev.notypie.application.service

import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.application.service.standup.StandupSummaryService
import dev.notypie.common.jsonMapper
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.command.createAgentConverseRequestEvent
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createStandupAnswerDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.ModalTemplateBuilder
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.LocalDate
import java.util.UUID

// An outbox row lives in `payload MEDIUMTEXT` (V23) and, in CDC mode, travels in a Debezium record that an update
// event fills with the row twice (before and after) under the producer's default max.request.size of 1 MiB. TEXT's
// 65,535 bytes used to reject the largest replies outright (review H1). This spec stages the biggest inputs the bot
// produces through the real services and the real codec, and checks the bytes that would be written. The payload is
// already JSON, and the record JSON-escapes it again as a string column, so escape-heavy text (control characters,
// backslashes) costs several bytes per character in the record — the record, not the column, sets the answer cap.
private const val MEDIUMTEXT_MAX_BYTES = 16_777_215
private const val TEXT_MAX_BYTES = 65_535
private const val KAFKA_DEFAULT_MAX_REQUEST_BYTES = 1_048_576

// The rest of a Debezium JSON record: the schema block, source metadata and the row's other columns.
private const val CDC_ENVELOPE_RESERVE_BYTES = 32_768

private fun OutboxMessage.payloadBytes(): Int = payload.toByteArray(charset = Charsets.UTF_8).size

// The payload column as it appears inside the record: a JSON string literal, escapes included.
private fun OutboxMessage.cdcColumnBytes(): Int =
    jsonMapper.writeValueAsString(payload).toByteArray(charset = Charsets.UTF_8).size

// A CDC update record: the column before and after, plus the envelope.
private fun OutboxMessage.cdcUpdateRecordBytes(): Int = 2 * cdcColumnBytes() + CDC_ENVELOPE_RESERVE_BYTES

class OutboxPayloadSizeGuardTest :
    BehaviorSpec({
        val port = CodecOutboundMessagePort()

        fun stubTransactionManager(): PlatformTransactionManager {
            val transactionManager = mockk<PlatformTransactionManager>()
            every { transactionManager.getTransaction(any()) } returns mockk<TransactionStatus>(relaxed = true)
            every { transactionManager.commit(any()) } just Runs
            every { transactionManager.rollback(any()) } just Runs
            return transactionManager
        }

        // What the service stages, encoded the way SlackOutboundStager writes it to the outbox.
        fun stagedAiAnswerRow(finalText: String): OutboxMessage {
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = finalText)
            val message = slot<OutboundMessage>()
            val basicInfo = slot<CommandBasicInfo>()
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(message), basicInfo = capture(basicInfo)) } returns
                mockk<CommandEvent<EventPayload>>(relaxed = true)
            AgentConverseService(
                agentGateway = gateway,
                agentSessionRepository = mockk(relaxed = true),
                agentTurnHistoryRepository = mockk(relaxed = true),
                outboundStager = stager,
                eventPublisher = mockk(relaxed = true),
                meterRegistry = SimpleMeterRegistry(),
                transactionManager = stubTransactionManager(),
                clock = createFixedUtcClock(),
            ).handleAgentConverse(event = createAgentConverseRequestEvent())
            return port.toRow(message = message.captured, basicInfo = basicInfo.captured)
        }

        given("an AI answer of 300,000 Korean characters, far over the staging cap") {
            // Three UTF-8 bytes per UTF-16 unit is the most a printable character takes; a surrogate pair is four bytes
            // for two units.
            val row = stagedAiAnswerRow(finalText = "가".repeat(n = 300_000))

            `when`("the staged reply is encoded into an outbox row") {
                then("it is over TEXT, as the uncapped answers that went missing were, yet fits MEDIUMTEXT") {
                    row.payloadBytes() shouldBeGreaterThan TEXT_MAX_BYTES
                    row.payloadBytes() shouldBeLessThanOrEqual MEDIUMTEXT_MAX_BYTES
                }

                then("the column twice, re-escaped, plus the envelope fits one Kafka record at the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        // The worst cases for the record: a C0 control character is \u00XX in the payload and \\u00XX in the record,
        // and a backslash doubles at each level. At the renderer's 139,200 characters these overflowed 1 MiB.
        given("an AI answer made of control characters, the longest JSON escape per character") {
            val row = stagedAiAnswerRow(finalText = "\u0001".repeat(n = 300_000))

            `when`("the staged reply is encoded into an outbox row") {
                then("it fits MEDIUMTEXT, because the cap bounds characters before they are escaped") {
                    row.payloadBytes() shouldBeLessThanOrEqual MEDIUMTEXT_MAX_BYTES
                }

                then("its CDC update record still fits one Kafka record at the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        given("an AI answer made of backslashes, as a pasted Windows path or regex is") {
            val row = stagedAiAnswerRow(finalText = "\\".repeat(n = 300_000))

            `when`("the staged reply is encoded into an outbox row") {
                then("its CDC update record fits one Kafka record at the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        given("a standup summary for the most members a routine allows, every answer at the modal cap") {
            // One one-character question leaves the largest per-member answer budget the modal hands out.
            val questions = listOf("Q")
            val answerCap =
                jsonMapper
                    .readTree(
                        ModalTemplateBuilder(restRequester = mockk(), slackApiToken = TEST_BOT_TOKEN)
                            .standupModalViewJson(
                                routineName = "Daily",
                                sessionDate = LocalDate.of(2026, 5, 4),
                                sessionUid = UUID.randomUUID(),
                                userId = "U0123456789",
                                noticeChannel = "D_NOTICE",
                                noticeMessageTs = "1700000000.000400",
                                questions = questions,
                            ),
                    ).path("blocks")
                    .first { it.path("type").asString() == "input" }
                    .path("element")
                    .path("max_length")
                    .asInt()
            val members = (1..Routine.MAX_MEMBERS).map { createRoutineMemberDto(userId = "U%010d".format(it)) }
            val sessionUid = UUID.randomUUID()
            val routineUid = UUID.randomUUID()
            val repository = mockk<StandupRepository>()
            every { repository.findSessionForSummary(sessionUid = sessionUid) } returns
                createStandupSessionDto(
                    sessionId = 1L,
                    sessionUid = sessionUid,
                    routineUid = routineUid,
                    answers =
                        members.map { member ->
                            createStandupAnswerDto(
                                userId = member.userId,
                                responses = questions.map { "가".repeat(n = answerCap) },
                            )
                        },
                )
            every { repository.getRoutine(routineUid = routineUid) } returns
                createRoutineDto(
                    routineUid = routineUid,
                    name = "가".repeat(n = Routine.MAX_NAME_LENGTH - 1),
                    questions = questions,
                    members = members,
                )
            every { repository.markSessionSummarized(sessionId = 1L, messageTs = any()) } returns true
            val saved = slot<OutboxMessage>()
            val outboxRepository = mockk<MessageOutboxRepository>()
            every { outboxRepository.save(capture(saved)) } answers { firstArg() }

            `when`("the summary row is staged") {
                StandupSummaryService(
                    standupRepository = repository,
                    outboxRepository = outboxRepository,
                    outboundMessagePort = port,
                    transactionManager = stubTransactionManager(),
                ).postSummary(
                    event =
                        StandupCutoffEvent(
                            sessionId = 1L,
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            sessionDate = LocalDate.of(2026, 5, 4),
                        ),
                )

                then("it is over TEXT, as the summaries that were never posted were, yet fits MEDIUMTEXT") {
                    saved.captured.payloadBytes() shouldBeGreaterThan TEXT_MAX_BYTES
                    saved.captured.payloadBytes() shouldBeLessThanOrEqual MEDIUMTEXT_MAX_BYTES
                }

                then("twice the payload plus the envelope fits one Kafka record at the default limit") {
                    saved.captured.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }
    })
