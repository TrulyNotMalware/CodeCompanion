package dev.notypie.application.service

import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.application.service.cve.notification.CveNotificationDispatcher
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
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.toChainHead
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.schema.createUndeliveredCveEvent
import dev.notypie.templates.ModalTemplateBuilder
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor

private const val MEDIUMTEXT_MAX_BYTES = 16_777_215
private const val TEXT_MAX_BYTES = 65_535
private const val KAFKA_DEFAULT_MAX_REQUEST_BYTES = 1_048_576
private const val CDC_ENVELOPE_RESERVE_BYTES = 32_768
private const val DIGEST_BATCH_SIZE = 50

private fun OutboxMessage.payloadBytes(): Int = payload.toByteArray(charset = Charsets.UTF_8).size

private fun OutboxMessage.cdcColumnBytes(): Int =
    jsonMapper.writeValueAsString(payload).toByteArray(charset = Charsets.UTF_8).size

private fun OutboxMessage.cdcUpdateRecordBytes(): Int = 2 * cdcColumnBytes() + CDC_ENVELOPE_RESERVE_BYTES

private fun OutboxMessage.parts(): Int = 1 + OutboundMessageCodec.decode(json = payload).continuation.size

class OutboxPayloadSizeGuardTest :
    BehaviorSpec({
        val port = CodecOutboundMessagePort()

        fun stagedAiAnswerRow(finalText: String): OutboxMessage {
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = finalText)
            val messages = slot<List<OutboundMessage>>()
            val basicInfo = slot<CommandBasicInfo>()
            val stager = mockk<OutboundMessageStager>()
            every { stager.stageInOrder(messages = capture(messages), basicInfo = capture(basicInfo)) } returns
                mockk<CommandEvent<EventPayload>>(relaxed = true)
            AgentConverseService(
                agentGateway = gateway,
                agentSessionRepository = mockk(relaxed = true),
                agentTurnHistoryRepository = mockk(relaxed = true),
                outboundStager = stager,
                eventPublisher = mockk(relaxed = true),
                meterRegistry = SimpleMeterRegistry(),
                transactionManager = createStubTransactionManager(),
                turnExecutor = Executor { it.run() },
                clock = createFixedUtcClock(),
            ).handleAgentConverse(event = createAgentConverseRequestEvent())
            return port.toChainHead(messages = messages.captured, basicInfo = basicInfo.captured)
        }

        fun stagedSummaryRow(answer: (Int) -> String, questions: List<String> = listOf("Q")): OutboxMessage {
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
                                responses = questions.map { answer(answerCap) },
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
            StandupSummaryService(
                standupRepository = repository,
                outboxRepository = outboxRepository,
                outboundMessagePort = port,
                transactionManager = createStubTransactionManager(),
            ).postSummary(
                event =
                    StandupCutoffEvent(
                        sessionId = 1L,
                        sessionUid = sessionUid,
                        routineUid = routineUid,
                        sessionDate = LocalDate.of(2026, 5, 4),
                    ),
            )
            return saved.captured
        }

        fun stagedDigestRow(text: (Int) -> String): OutboxMessage {
            val dayLimit = DIGEST_BATCH_SIZE * 10
            val events =
                (1..dayLimit).map { index ->
                    createUndeliveredCveEvent(
                        eventId = index.toLong(),
                        userId = "U1",
                        topicDisplayName = text(128),
                        title = text(512),
                        aiSummary = text(700),
                    )
                }
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            every { deliveryRepository.dbNow() } returns LocalDateTime.of(2026, 7, 14, 10, 0)
            every {
                deliveryRepository.findUndeliveredByUser(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = any(),
                    limit = DIGEST_BATCH_SIZE,
                )
            } returns events.take(n = DIGEST_BATCH_SIZE)
            every {
                deliveryRepository.findUndeliveredForUser(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    userId = "U1",
                    since = any(),
                    doneBefore = any(),
                    limit = dayLimit,
                )
            } returns events
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            val saved = slot<OutboxMessage>()
            val outboxRepository = mockk<MessageOutboxRepository>()
            every { outboxRepository.save(capture(saved)) } answers { firstArg() }
            CveNotificationDispatcher(
                cveDeliveryRepository = deliveryRepository,
                outboxRepository = outboxRepository,
                outboundMessagePort = port,
                transactionManager = createStubTransactionManager(),
                batchSize = DIGEST_BATCH_SIZE,
                digestSendAt = LocalTime.of(9, 0),
                digestZone = ZoneOffset.UTC,
                digestSummaryMaxLength = 700,
                deliveryHorizonDays = 7,
                clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC),
            ).digestTick()
            return saved.captured
        }

        given("an AI answer of 300,000 Korean characters, far over the answer cap") {
            val row = stagedAiAnswerRow(finalText = "가".repeat(n = 300_000))

            `when`("the staged chain head is encoded into an outbox row") {
                then("it carries several parts, is over TEXT and fits MEDIUMTEXT") {
                    row.parts() shouldBeGreaterThan 1
                    row.payloadBytes() shouldBeGreaterThan TEXT_MAX_BYTES
                    row.payloadBytes() shouldBeLessThanOrEqual MEDIUMTEXT_MAX_BYTES
                }

                then("its CDC update record fits one Kafka record at the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        given("an AI answer made of control characters, the longest JSON escape per character") {
            val row = stagedAiAnswerRow(finalText = "\u0001".repeat(n = 300_000))

            `when`("the staged chain head is encoded into an outbox row") {
                then("its CDC update record fits one Kafka record at the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        given("an AI answer made of backslashes, as a pasted Windows path or regex is") {
            val row = stagedAiAnswerRow(finalText = "\\".repeat(n = 300_000))

            `when`("the staged chain head is encoded into an outbox row") {
                then("its CDC update record fits one Kafka record at the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        given("a standup summary for the most members a routine allows, every answer at the modal cap") {
            val cases =
                listOf(
                    "Korean answers" to stagedSummaryRow(answer = { "가".repeat(n = it) }),
                    "answers of backslashes and quotes" to stagedSummaryRow(answer = { "\\\"".repeat(n = it / 2) }),
                    "eight questions of control characters and answers of backslashes" to
                        stagedSummaryRow(
                            answer = { "\\".repeat(n = it) },
                            questions = List(size = Routine.MAX_QUESTIONS) { "\u0001".repeat(n = 199) },
                        ),
                )

            cases.forEach { (name, row) ->
                `when`("the summary chain head is staged with $name") {
                    then("it carries several parts and fits MEDIUMTEXT") {
                        row.parts() shouldBeGreaterThan 1
                        row.payloadBytes() shouldBeLessThanOrEqual MEDIUMTEXT_MAX_BYTES
                    }

                    then("its CDC update record fits one Kafka record at the default limit") {
                        row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                    }
                }
            }
        }

        given("a standup summary whose answers at the modal cap are control characters") {
            val row = stagedSummaryRow(answer = { "\u0001".repeat(n = it) })

            `when`("the summary chain head is staged") {
                then("the control characters are stripped, so its CDC update record fits the default limit") {
                    row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                }
            }
        }

        given("one user's whole digest day at the longest topic names, titles and summaries") {
            val cases =
                listOf(
                    "Korean text" to stagedDigestRow(text = { "가".repeat(n = it) }),
                    "backslashes" to stagedDigestRow(text = { "\\".repeat(n = it) }),
                    "control characters" to stagedDigestRow(text = { "\u0001".repeat(n = it) }),
                    "ampersands, which escaping widens fivefold" to stagedDigestRow(text = { "&".repeat(n = it) }),
                )

            cases.forEach { (name, row) ->
                `when`("the digest chain head is staged from $name") {
                    then("its CDC update record fits one Kafka record at the default limit") {
                        row.cdcUpdateRecordBytes() shouldBeLessThanOrEqual KAFKA_DEFAULT_MAX_REQUEST_BYTES
                    }
                }
            }
        }
    })
