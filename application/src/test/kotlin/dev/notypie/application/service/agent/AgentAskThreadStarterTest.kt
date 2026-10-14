package dev.notypie.application.service.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.MutableClock
import dev.notypie.application.outbox.QueuedExecutor
import dev.notypie.application.outbox.createOutboxJpaContext
import dev.notypie.application.service.relay.STATUS_WRITE_ATTEMPTS
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.createAgentQuestionMessage
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.agent.AgentAskTurnRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepositoryImpl
import dev.notypie.repository.agent.JpaAgentTurnHistoryRepository
import dev.notypie.repository.agent.schema.AgentAskTurnSchema
import dev.notypie.repository.agent.schema.AgentAskTurnStatus
import dev.notypie.repository.agent.schema.AgentTurnOutcome
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessageDeliveredEvent
import dev.notypie.schema.createAgentAskTurnSchema
import dev.notypie.schema.createOutboxMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import org.springframework.core.retry.RetryException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class StarterLane(
    outboxRepository: MessageOutboxRepository,
    askTurnRepository: AgentAskTurnRepository,
    appConfig: AppConfig,
) {
    val clock = MutableClock(current = Instant.parse("2026-10-12T00:00:00Z"))
    val submitted = mutableListOf<AgentConverseRequestEvent>()
    val offered = mutableListOf<AgentConverseRequestEvent>()
    val leases = mutableListOf<AgentTurnLease>()
    val abandoned = mutableListOf<AgentConverseRequestEvent>()
    var onSubmit: (AgentTurnLease) -> Boolean = { true }
    var onOffer: (AgentTurnLease) -> Boolean = { true }
    var onAbandon: (AgentTurnLease) -> Boolean = { lease ->
        lease.abandon()
        true
    }
    private val meterRegistry = SimpleMeterRegistry()
    private val converseService =
        mockk<AgentConverseService> {
            every { submit(event = any(), lease = any()) } answers {
                submitted += firstArg<AgentConverseRequestEvent>()
                leases += secondArg<AgentTurnLease>()
                onSubmit(secondArg())
            }
            every { offer(event = any(), lease = any()) } answers {
                offered += firstArg<AgentConverseRequestEvent>()
                leases += secondArg<AgentTurnLease>()
                onOffer(secondArg())
            }
            every { abandon(event = any(), lease = any()) } answers {
                abandoned += firstArg<AgentConverseRequestEvent>()
                onAbandon(secondArg())
            }
        }
    val starter =
        AgentAskThreadStarter(
            outboxRepository = outboxRepository,
            askTurnRepository = askTurnRepository,
            agentConverseService = converseService,
            retryService = RetryService(),
            meterRegistry = meterRegistry,
            clock = clock,
            appConfig = appConfig,
        )

    fun counted(outcome: String): Double =
        meterRegistry.counter(AgentConverseService.METRIC_TURNS, "outcome", outcome).count()
}

private class FlakyOutbox(
    delegate: MessageOutboxRepository,
    failures: Int,
) {
    val reads = AtomicInteger(0)
    val repository: MessageOutboxRepository =
        mockk {
            every { findById(any<String>()) } answers {
                if (reads.incrementAndGet() <= failures) throw DataAccessResourceFailureException("outbox read failed")
                delegate.findById(firstArg<String>())
            }
        }
}

private fun ledgerChangingAfterFind(delegate: AgentAskTurnRepository, afterFind: () -> Unit): AgentAskTurnRepository =
    mockk {
        every { findResumable(pendingBefore = any(), orphanedBefore = any(), limit = any()) } answers {
            delegate
                .findResumable(pendingBefore = firstArg(), orphanedBefore = secondArg(), limit = thirdArg())
                .also { afterFind() }
        }
        every { releaseOrphan(eventId = any(), attemptCount = any(), orphanedBefore = any(), now = any()) } answers {
            delegate.releaseOrphan(
                eventId = firstArg(),
                attemptCount = secondArg(),
                orphanedBefore = thirdArg(),
                now = arg(n = 3),
            )
        }
        every { finish(eventId = any(), attemptCount = any(), status = any(), now = any()) } answers {
            delegate.finish(eventId = firstArg(), attemptCount = secondArg(), status = thirdArg(), now = arg(n = 3))
        }
    }

class AgentAskThreadStarterTest :
    BehaviorSpec({
        val context = createOutboxJpaContext()
        afterSpec { context.close() }
        val outboxRepository = context.getBean(MessageOutboxRepository::class.java)
        val askTurnRepository = context.getBean(AgentAskTurnRepository::class.java)
        val basicInfo = createCommandBasicInfo()
        val gracePassed = ASK_PENDING_GRACE.plusSeconds(1L)

        fun lane(
            appConfig: AppConfig = AppConfig(),
            outbox: MessageOutboxRepository = outboxRepository,
            ledger: AgentAskTurnRepository = askTurnRepository,
        ): StarterLane {
            askTurnRepository.deleteAllInBatch()
            outboxRepository.deleteAllInBatch()
            return StarterLane(outboxRepository = outbox, askTurnRepository = ledger, appConfig = appConfig)
        }

        fun storedQuestion(prompt: String = "why is CI red?"): String =
            outboxRepository
                .save(
                    CodecOutboundMessagePort()
                        .toRow(message = createAgentQuestionMessage(prompt = prompt), basicInfo = basicInfo),
                ).eventId

        fun storedTurn(
            prompt: String,
            updatedAt: LocalDateTime,
            status: AgentAskTurnStatus = AgentAskTurnStatus.PENDING,
            attemptCount: Int = 0,
            runCount: Int = 0,
            createdAt: LocalDateTime = updatedAt,
        ): String =
            askTurnRepository
                .saveAndFlush(
                    createAgentAskTurnSchema(
                        eventId = storedQuestion(prompt = prompt),
                        status = status,
                        attemptCount = attemptCount,
                        runCount = runCount,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    ),
                ).eventId

        fun turnOf(eventId: String): AgentAskTurnSchema =
            checkNotNull(askTurnRepository.findByIdOrNull(id = eventId)) { "no ask turn $eventId" }

        fun deliveryOf(
            eventId: String,
            commandDetailType: CommandDetailType = CommandDetailType.AGENT_ASK_QUESTION,
            messageTs: String = TEST_MESSAGE_TS,
        ): MessageDeliveredEvent =
            MessageDeliveredEvent(
                eventId = UUID.fromString(eventId),
                commandDetailType = commandDetailType,
                messageTs = messageTs,
            )

        fun loggedDuring(level: Level, action: () -> Unit): List<String> {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(AgentAskThreadStarter::class.java.packageName) as Logger
            logger.addAppender(appender)
            try {
                action()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list.filter { it.level == level }.map { it.formattedMessage }
        }

        val transactionManager = context.getBean(JpaTransactionManager::class.java)
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val auditRepository =
            AgentTurnHistoryRepositoryImpl(
                jpaAgentTurnHistoryRepository = context.getBean(JpaAgentTurnHistoryRepository::class.java),
            )

        class RealServiceLane(
            gateway: AgentGateway,
            turnExecutor: Executor,
        ) {
            val clock = MutableClock(current = Instant.parse("2026-10-12T00:00:00Z"))
            val staged = mutableListOf<OutboundMessage>()
            var noticeFails = false
            val outboundStager =
                mockk<OutboundMessageStager> {
                    every { stage(message = capture(staged), basicInfo = any()) } returns mockk(relaxed = true)
                    every { stageInOrder(messages = any(), basicInfo = any()) } answers {
                        staged += firstArg<List<OutboundMessage>>()
                        mockk(relaxed = true)
                    }
                }
            private val noticeIds = mutableListOf<String>()
            val eventPublisher =
                mockk<EventPublisher> {
                    every { publishEvent(events = any()) } answers {
                        check(!noticeFails) { "outbox unavailable" }
                        noticeIds += outboxRepository.save(createOutboxMessage()).eventId
                    }
                }
            val starter =
                AgentAskThreadStarter(
                    outboxRepository = outboxRepository,
                    askTurnRepository = askTurnRepository,
                    agentConverseService =
                        AgentConverseService(
                            agentGateway = gateway,
                            agentSessionRepository = mockk(relaxed = true),
                            agentTurnHistoryRepository = auditRepository,
                            outboundStager = outboundStager,
                            eventPublisher = eventPublisher,
                            meterRegistry = SimpleMeterRegistry(),
                            transactionManager = transactionManager,
                            turnExecutor = turnExecutor,
                            clock = clock,
                        ),
                    retryService = RetryService(),
                    meterRegistry = SimpleMeterRegistry(),
                    clock = clock,
                    appConfig = AppConfig(),
                )

            fun committedNotices(): Int = noticeIds.count { outboxRepository.existsById(it) }
        }

        fun realLane(gateway: AgentGateway, turnExecutor: Executor): RealServiceLane {
            askTurnRepository.deleteAllInBatch()
            outboxRepository.deleteAllInBatch()
            jdbc.update("DELETE FROM agent_turn_history")
            return RealServiceLane(gateway = gateway, turnExecutor = turnExecutor)
        }

        fun auditOutcomes(): List<String> =
            jdbc.queryForList("SELECT outcome FROM agent_turn_history", String::class.java).filterNotNull()

        fun busyGateway(): AgentGateway = mockk { every { converse(request = any()) } returns AgentTurnResult.Busy }

        given("a delivered message that is not an asked question") {
            `when`("a standup summary is delivered") {
                val lane = lane()
                lane.starter.onDelivered(
                    event =
                        deliveryOf(eventId = storedQuestion(), commandDetailType = CommandDetailType.STANDUP_SUMMARY),
                )

                then("no turn is recorded or submitted") {
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }

            `when`("a plain-text delivery with a ts arrives, as the /ask notice staged for a refused channel does") {
                val lane = lane()
                val eventId = storedQuestion()
                val warnings =
                    loggedDuring(level = Level.WARN) {
                        lane.starter.onDelivered(
                            event = deliveryOf(eventId = eventId, commandDetailType = CommandDetailType.SIMPLE_TEXT),
                        )
                    }

                then("it is ignored before the outbox is read: no warning, no turn recorded or submitted") {
                    warnings.shouldBeEmpty()
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }
        }

        given("an asked question delivered without a Slack ts") {
            `when`("the delivery event carries a blank ts") {
                val lane = lane()
                val eventId = storedQuestion()
                val warnings =
                    loggedDuring(level = Level.WARN) {
                        lane.starter.onDelivered(event = deliveryOf(eventId = eventId, messageTs = ""))
                    }

                then("there is no thread to answer in: one warning names the event, nothing is recorded or submitted") {
                    warnings shouldBe
                        listOf("Asked question eventId=$eventId was delivered without a ts; no turn can be threaded")
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }
        }

        given("an asked question whose root message Slack just delivered") {
            val lane = lane()
            val eventId = storedQuestion(prompt = "why is CI red?")
            val deliveredAt = lane.clock.now

            `when`("the delivery event arrives twice before the turn starts") {
                repeat(times = 2) { lane.starter.onDelivered(event = deliveryOf(eventId = eventId)) }
                val recorded = turnOf(eventId = eventId)

                then("the turn is recorded once as PENDING with the posted ts and the clock's time") {
                    askTurnRepository.count() shouldBe 1L
                    recorded.messageTs shouldBe TEST_MESSAGE_TS
                    recorded.status shouldBe AgentAskTurnStatus.PENDING
                    recorded.attemptCount shouldBe 0
                    recorded.createdAt shouldBe deliveredAt
                    recorded.updatedAt shouldBe deliveredAt
                }

                then("exactly one turn is submitted, threaded under the posted question") {
                    val request = lane.submitted.single()
                    request.idempotencyKey shouldBe basicInfo.idempotencyKey
                    request.type shouldBe CommandDetailType.AGENT_CONVERSE
                    request.payload.prompt shouldBe "why is CI red?"
                    request.payload.threadId shouldBe TEST_MESSAGE_TS
                    request.payload.requesterName shouldBe TEST_USER_NAME
                    request.payload.channelName shouldBe TEST_CHANNEL_NAME
                    request.payload.responseBasicInfo shouldBe basicInfo
                    lane.offered.shouldBeEmpty()
                }
            }

            `when`("the sweep runs while the turn still waits in this instance's queue") {
                lane.clock.advance(by = Duration.ofMinutes(5L))
                val resumed = lane.starter.resumeOnce()

                then("the turn is not handed to the executor a second time") {
                    resumed shouldBe 0
                    lane.submitted shouldHaveSize 1
                    lane.offered.shouldBeEmpty()
                }
            }

            `when`("the queued turn begins") {
                val begun = lane.leases.single().begin()
                val running = turnOf(eventId = eventId)

                then("the run owns the turn: IN_PROGRESS on attempt 1, stamped with the clock's time") {
                    begun shouldBe true
                    running.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    running.attemptCount shouldBe 1
                    running.updatedAt shouldBe lane.clock.now
                    running.createdAt shouldBe deliveredAt
                }
            }

            `when`("the question is delivered again, after a second post, while the turn runs") {
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId, messageTs = "9.9"))

                then("no second turn is submitted and the first post's ts is kept") {
                    lane.submitted shouldHaveSize 1
                    turnOf(eventId = eventId).messageTs shouldBe TEST_MESSAGE_TS
                }
            }

            `when`("the run settles its outcome and the sweep runs long after") {
                lane.leases.single().settle()
                val settled = turnOf(eventId = eventId)
                lane.clock.advance(by = Duration.ofHours(2L))
                val resumed = lane.starter.resumeOnce()

                then("the turn is COMPLETED on the same attempt and is never picked up again") {
                    settled.status shouldBe AgentAskTurnStatus.COMPLETED
                    settled.attemptCount shouldBe 1
                    resumed shouldBe 0
                    lane.submitted shouldHaveSize 1
                    lane.offered.shouldBeEmpty()
                    lane.abandoned.shouldBeEmpty()
                    turnOf(eventId = eventId).status shouldBe AgentAskTurnStatus.COMPLETED
                }
            }
        }

        given("an asked question delivered again while its turn waits and is not queued in this instance") {
            val lane = lane()
            lane.onOffer = { false }
            val eventId = storedTurn(prompt = "posted twice", updatedAt = lane.clock.now)
            val before = jdbc.queryForMap("SELECT * FROM agent_ask_turn WHERE event_id = ?", eventId)

            `when`("the executor is full") {
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId, messageTs = "2.2"))
                val after = jdbc.queryForMap("SELECT * FROM agent_ask_turn WHERE event_id = ?", eventId)

                then("the turn is offered, not submitted, under its first ts, and the refusal changes nothing") {
                    lane.submitted.shouldBeEmpty()
                    lane.offered
                        .single()
                        .payload.threadId shouldBe TEST_MESSAGE_TS
                    after shouldBe before
                }
            }

            `when`("the grace period passes and the sweep finds room") {
                lane.onOffer = { true }
                lane.clock.advance(by = gracePassed)
                val resumed = lane.starter.resumeOnce()

                then("the turn is offered again and accepted") {
                    resumed shouldBe 1
                    lane.offered shouldHaveSize 2
                }
            }
        }

        given("a turn the sidecar reports busy") {
            val lane = lane()
            val eventId = storedQuestion()
            lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
            val firstRun = lane.leases.single()
            firstRun.begin()

            `when`("the first run gives the turn back") {
                val owed = firstRun.giveBack()
                val givenBack = turnOf(eventId = eventId)

                then("the answer-later notice is owed; the turn waits again on that attempt with its run taken back") {
                    owed shouldBe BusyNotice.ANSWER_LATER
                    givenBack.status shouldBe AgentAskTurnStatus.PENDING
                    givenBack.attemptCount shouldBe 1
                    givenBack.runCount shouldBe 0
                }
            }

            `when`("the sweep runs within the grace period and again after it") {
                lane.clock.advance(by = ASK_PENDING_GRACE)
                val early = lane.starter.resumeOnce()
                lane.clock.advance(by = Duration.ofSeconds(1L))
                val late = lane.starter.resumeOnce()

                then("only the later sweep offers the turn again and counts it as resumed") {
                    early shouldBe 0
                    late shouldBe 1
                    lane.submitted shouldHaveSize 1
                    lane.offered shouldHaveSize 1
                    lane.counted(outcome = "resumed") shouldBe 1.0
                }
            }

            `when`("the second run begins and the first run then writes an outcome") {
                val begun = lane.leases.last().begin()
                val running = turnOf(eventId = eventId)

                then("the second run owns attempt 2 and is the only run counted") {
                    begun shouldBe true
                    running.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    running.attemptCount shouldBe 2
                    running.runCount shouldBe 1
                }

                then("every outcome the first run then writes is refused and changes nothing") {
                    shouldThrow<IllegalStateException> { firstRun.settle() }
                    shouldThrow<IllegalStateException> { firstRun.giveBack() }
                    shouldThrow<IllegalStateException> { firstRun.abandon() }
                    val unchanged = turnOf(eventId = eventId)
                    unchanged.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    unchanged.attemptCount shouldBe 2
                }
            }
        }

        given("a turn that waited in the queue until another instance began it") {
            val lane = lane()
            val eventId = storedQuestion()
            lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
            askTurnRepository.begin(eventId = eventId, attemptCount = 0, now = lane.clock.now)

            `when`("the queued turn tries to begin") {
                val begun = lane.leases.single().begin()

                then("it is refused and the attempt count stays with the other run") {
                    begun shouldBe false
                    turnOf(eventId = eventId).attemptCount shouldBe 1
                }
            }
        }

        given("a turn still queued when the executor shuts down") {
            val lane = lane()
            val eventId = storedQuestion()
            lane.starter.onDelivered(event = deliveryOf(eventId = eventId))

            `when`("the executor discards it unstarted") {
                val resumesLater = lane.leases.single().discarded()

                then("no notice is owed: the turn stays PENDING and was never begun") {
                    resumesLater shouldBe true
                    val waiting = turnOf(eventId = eventId)
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe 0
                }
            }

            `when`("a sweep runs after the grace period") {
                lane.clock.advance(by = gracePassed)
                val resumed = lane.starter.resumeOnce()

                then("the turn is offered to the executor again") {
                    resumed shouldBe 1
                    lane.offered shouldHaveSize 1
                }
            }
        }

        given("a turn the executor rejects") {
            `when`("the overload notice is written and settles the turn") {
                val lane = lane()
                lane.onSubmit = { lease ->
                    lease.settle()
                    false
                }
                val eventId = storedQuestion()
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
                val settled = turnOf(eventId = eventId)
                lane.clock.advance(by = gracePassed)
                val resumed = lane.starter.resumeOnce()

                then("the turn is COMPLETED without a run and the sweep leaves it alone") {
                    settled.status shouldBe AgentAskTurnStatus.COMPLETED
                    settled.attemptCount shouldBe 0
                    resumed shouldBe 0
                    lane.submitted shouldHaveSize 1
                    lane.offered.shouldBeEmpty()
                }
            }

            `when`("the overload notice cannot be written") {
                val lane = lane()
                lane.onSubmit = { false }
                val eventId = storedQuestion()
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
                val waiting = turnOf(eventId = eventId)
                lane.onSubmit = { true }
                lane.clock.advance(by = gracePassed)
                val resumed = lane.starter.resumeOnce()

                then("the turn stays PENDING and the next sweep offers it again") {
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    resumed shouldBe 1
                    lane.offered shouldHaveSize 1
                }
            }
        }

        given("a delivery tagged as an asked question whose outbox row cannot yield the question") {
            `when`("the row is gone") {
                val lane = lane()
                val eventId = UUID.randomUUID().toString()
                val warnings =
                    loggedDuring(level = Level.WARN) {
                        lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
                    }

                then("a warning names the event and no turn is recorded or submitted") {
                    warnings shouldBe
                        listOf("Asked question eventId=$eventId is gone or carries no question; no turn starts")
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }

            `when`("the row carries other content") {
                val lane = lane()
                val eventId =
                    outboxRepository
                        .save(
                            CodecOutboundMessagePort().toRow(
                                message =
                                    OutboundMessage.ChannelMessage(
                                        target = ConversationTarget(id = basicInfo.channel),
                                        content = MessageContent.Text(headline = null, markdown = "plain"),
                                        detailType = CommandDetailType.AGENT_ASK_QUESTION,
                                    ),
                                basicInfo = basicInfo,
                            ),
                        ).eventId
                val warnings =
                    loggedDuring(level = Level.WARN) {
                        lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
                    }

                then("a warning names the event and no turn is recorded or submitted") {
                    warnings shouldBe
                        listOf("Asked question eventId=$eventId is gone or carries no question; no turn starts")
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }

            `when`("the row's payload cannot be decoded") {
                val outbox = FlakyOutbox(delegate = outboxRepository, failures = 0)
                val lane = lane(outbox = outbox.repository)
                val eventId = outboxRepository.save(createOutboxMessage(payload = "{}")).eventId
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId))

                then("the row is read once, not retried, and no turn is recorded or submitted") {
                    outbox.reads.get() shouldBe 1
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }
        }

        given("a delivery whose question read or ledger write fails") {
            `when`("the outbox read fails once and then succeeds") {
                val outbox = FlakyOutbox(delegate = outboxRepository, failures = 1)
                val lane = lane(outbox = outbox.repository)
                val eventId = storedQuestion(prompt = "after a blip")
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId))

                then("the second attempt records the turn and submits it, without another outbox read") {
                    outbox.reads.get() shouldBe 2
                    turnOf(eventId = eventId).status shouldBe AgentAskTurnStatus.PENDING
                    lane.submitted.map { it.payload.prompt } shouldContainExactly listOf("after a blip")
                }
            }

            `when`("the ledger insert fails once and then succeeds") {
                val inserts = AtomicInteger(0)
                val ledger =
                    mockk<AgentAskTurnRepository> {
                        every { saveAndFlush(any<AgentAskTurnSchema>()) } answers {
                            if (inserts.incrementAndGet() == 1) throw DataAccessResourceFailureException("down")
                            askTurnRepository.saveAndFlush(firstArg<AgentAskTurnSchema>())
                        }
                    }
                val lane = lane(ledger = ledger)
                val eventId = storedQuestion()
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId))

                then("the second attempt records the turn once, with no lookup before the insert, and leases it") {
                    inserts.get() shouldBe 2
                    askTurnRepository.count() shouldBe 1L
                    turnOf(eventId = eventId).messageTs shouldBe TEST_MESSAGE_TS
                    lane.submitted shouldHaveSize 1
                    (lane.leases.single() === AgentTurnLease.Unleased) shouldBe false
                }
            }

            `when`("the ledger insert is stored but reports a failure, and the executor is full") {
                val attempts = AtomicInteger(0)
                val ledger =
                    mockk<AgentAskTurnRepository> {
                        every { saveAndFlush(any<AgentAskTurnSchema>()) } answers {
                            val attempt = attempts.incrementAndGet()
                            val stored = askTurnRepository.saveAndFlush(firstArg<AgentAskTurnSchema>())
                            if (attempt == 1) throw DataAccessResourceFailureException("acknowledgement lost")
                            stored
                        }
                        every { findById(any<String>()) } answers { askTurnRepository.findById(firstArg<String>()) }
                    }
                val lane = lane(ledger = ledger)
                lane.onOffer = { false }
                val eventId = storedQuestion()
                lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
                val waiting = turnOf(eventId = eventId)
                val sweeper =
                    StarterLane(
                        outboxRepository = outboxRepository,
                        askTurnRepository = askTurnRepository,
                        appConfig = AppConfig(),
                    )
                sweeper.clock.advance(by = gracePassed)
                val resumed = sweeper.starter.resumeOnce()

                then("the retry finds the stored row and offers it: the refusal leaves it PENDING, with no notice") {
                    attempts.get() shouldBe 2
                    lane.submitted.shouldBeEmpty()
                    lane.offered shouldHaveSize 1
                    (lane.leases.single() === AgentTurnLease.Unleased) shouldBe false
                    askTurnRepository.count() shouldBe 1L
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe 0
                }

                then("a sweep after the grace period offers the turn again") {
                    resumed shouldBe 1
                    sweeper.offered shouldHaveSize 1
                }
            }

            `when`("the question is read but the ledger insert fails on every attempt") {
                val inserts = AtomicInteger(0)
                val ledger =
                    mockk<AgentAskTurnRepository> {
                        every { saveAndFlush(any<AgentAskTurnSchema>()) } answers {
                            inserts.incrementAndGet()
                            throw DataAccessResourceFailureException("ledger table missing")
                        }
                    }
                val lane = lane(ledger = ledger)
                val eventId = storedQuestion(prompt = "still answered")
                val errors =
                    loggedDuring(level = Level.ERROR) {
                        lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
                    }

                then("the turn starts once without a lease, threaded under the delivered ts") {
                    val request = lane.submitted.single()
                    request.payload.prompt shouldBe "still answered"
                    request.payload.threadId shouldBe TEST_MESSAGE_TS
                    (lane.leases.single() === AgentTurnLease.Unleased) shouldBe true
                }

                then("the lost durability is logged with the event and its idempotency key, and no row exists") {
                    inserts.get() shouldBe STATUS_WRITE_ATTEMPTS.toInt()
                    errors shouldBe
                        listOf(
                            "Recording asked question eventId=$eventId idempotencyKey=${basicInfo.idempotencyKey} " +
                                "failed; its turn starts without a ledger row",
                        )
                    askTurnRepository.count() shouldBe 0L
                }
            }

            `when`("the outbox read fails on every attempt") {
                val outbox = FlakyOutbox(delegate = outboxRepository, failures = Int.MAX_VALUE)
                val lane = lane(outbox = outbox.repository)
                val eventId = storedQuestion()

                then("the failure reaches the relay after as many attempts as its status write gets") {
                    shouldThrow<RetryException> { lane.starter.onDelivered(event = deliveryOf(eventId = eventId)) }
                    outbox.reads.get() shouldBe STATUS_WRITE_ATTEMPTS.toInt()
                    askTurnRepository.count() shouldBe 0L
                    lane.submitted.shouldBeEmpty()
                }
            }
        }

        given("a recorded turn whose question row is gone by the time the sweep runs") {
            val lane = lane()
            val eventId =
                askTurnRepository
                    .saveAndFlush(createAgentAskTurnSchema(updatedAt = lane.clock.now.minus(gracePassed)))
                    .eventId

            `when`("the sweep runs") {
                val warnings = loggedDuring(level = Level.WARN) { lane.starter.resumeOnce() }

                then("a warning names the event, the turn is ABANDONED and counted, and nothing is offered") {
                    warnings shouldBe
                        listOf("Asked question eventId=$eventId is gone or carries no question; abandoning its turn")
                    turnOf(eventId = eventId).status shouldBe AgentAskTurnStatus.ABANDONED
                    lane.counted(outcome = "abandoned") shouldBe 1.0
                    lane.offered.shouldBeEmpty()
                }
            }
        }

        given("a recorded turn whose question row is gone and which another run begins during the sweep") {
            val afterFind = mutableListOf<() -> Unit>()
            val changingLedger =
                ledgerChangingAfterFind(delegate = askTurnRepository) { afterFind.forEach { change -> change() } }
            val lane = lane(ledger = changingLedger)
            val now = lane.clock.now
            val eventId =
                askTurnRepository
                    .saveAndFlush(createAgentAskTurnSchema(updatedAt = now.minus(gracePassed)))
                    .eventId
            afterFind += { askTurnRepository.begin(eventId = eventId, attemptCount = 0, now = now) }

            `when`("the sweep runs") {
                val warnings = loggedDuring(level = Level.WARN) { lane.starter.resumeOnce() }

                then("the lost abandon is neither warned about nor counted, and the row stays with that run") {
                    warnings.shouldBeEmpty()
                    lane.counted(outcome = "abandoned") shouldBe 0.0
                    val running = turnOf(eventId = eventId)
                    running.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    running.attemptCount shouldBe 1
                }
            }
        }

        given("turns left behind by an instance that crashed or restarted") {
            val lane = lane()
            val now = lane.clock.now
            val sidecarTimeout = Duration.ofSeconds(AppConfig().agent.sidecar.requestTimeoutSeconds)
            val orphanedBefore = now.minus(sidecarTimeout).minus(ASK_ORPHAN_MARGIN)
            val orphaned =
                storedTurn(
                    prompt = "orphaned",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = 1,
                    updatedAt = orphanedBefore.minusSeconds(1L),
                )
            val running =
                storedTurn(
                    prompt = "running",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = 1,
                    updatedAt = orphanedBefore,
                )
            storedTurn(prompt = "waiting", updatedAt = now.minus(gracePassed))
            val fresh = storedTurn(prompt = "fresh", updatedAt = now.minus(ASK_PENDING_GRACE))

            `when`("the sweep runs") {
                val resumed = lane.starter.resumeOnce()

                then("the orphaned run and the waiting turn are offered, least recently touched first") {
                    resumed shouldBe 2
                    lane.offered.map { it.payload.prompt } shouldContainExactly listOf("orphaned", "waiting")
                    lane.offered.map { it.payload.threadId }.distinct() shouldContainExactly listOf(TEST_MESSAGE_TS)
                    lane.counted(outcome = "resumed") shouldBe 2.0
                }

                then("the orphaned run was released on its attempt, stamped with the clock's time") {
                    val released = turnOf(eventId = orphaned)
                    released.status shouldBe AgentAskTurnStatus.PENDING
                    released.attemptCount shouldBe 1
                    released.updatedAt shouldBe now
                }

                then("a run touched at the cutoff and a turn inside the grace period are left alone") {
                    turnOf(eventId = running).status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    turnOf(eventId = running).updatedAt shouldBe orphanedBefore
                    turnOf(eventId = fresh).updatedAt shouldBe now.minus(ASK_PENDING_GRACE)
                }
            }

            `when`("the resubmitted orphan begins") {
                val begun = lane.leases.first().begin()

                then("it runs as attempt 2") {
                    begun shouldBe true
                    turnOf(eventId = orphaned).attemptCount shouldBe 2
                }
            }
        }

        given("orphaned runs that change hands between the sweep's read and its release") {
            val afterFind = mutableListOf<() -> Unit>()
            val changingLedger =
                ledgerChangingAfterFind(delegate = askTurnRepository) { afterFind.forEach { change -> change() } }
            val lane = lane(ledger = changingLedger)
            lane.onAbandon = { false }
            val now = lane.clock.now
            val settled =
                storedTurn(
                    prompt = "settled meanwhile",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = ASK_MAX_RUNS,
                    runCount = ASK_MAX_RUNS,
                    updatedAt = now.minusMinutes(10L),
                )
            val retaken =
                storedTurn(
                    prompt = "retaken meanwhile",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = 1,
                    runCount = 1,
                    updatedAt = now.minusMinutes(9L),
                )
            afterFind += {
                askTurnRepository.finish(
                    eventId = settled,
                    attemptCount = ASK_MAX_RUNS,
                    status = AgentAskTurnStatus.COMPLETED.name,
                    now = now,
                )
                askTurnRepository.releaseOrphan(eventId = retaken, attemptCount = 1, orphanedBefore = now, now = now)
                askTurnRepository.begin(eventId = retaken, attemptCount = 1, now = now)
            }

            `when`("the sweep runs") {
                val resumed = lane.starter.resumeOnce()

                then("the lost releases skip both rows: nothing is offered, abandoned or counted") {
                    resumed shouldBe 0
                    lane.offered.shouldBeEmpty()
                    lane.abandoned.shouldBeEmpty()
                    lane.counted(outcome = "resumed") shouldBe 0.0
                }

                then("each row keeps the state its new owner gave it") {
                    turnOf(eventId = settled).status shouldBe AgentAskTurnStatus.COMPLETED
                    val running = turnOf(eventId = retaken)
                    running.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    running.attemptCount shouldBe 2
                }
            }
        }

        given("more waiting turns than one sweep takes") {
            val lane = lane()
            val oldest = lane.clock.now.minusMinutes(30L)
            val prompts = (0..ASK_RESUME_BATCH_SIZE).map { index -> "question $index" }
            prompts.forEachIndexed { index, prompt ->
                storedTurn(prompt = prompt, updatedAt = oldest.plusSeconds(index.toLong()))
            }

            `when`("the sweep runs once") {
                val resumed = lane.starter.resumeOnce()

                then("it offers exactly one batch, least recently touched first, and leaves the newest turn") {
                    resumed shouldBe ASK_RESUME_BATCH_SIZE
                    lane.offered.map { it.payload.prompt } shouldContainExactly prompts.take(ASK_RESUME_BATCH_SIZE)
                }
            }
        }

        given("a scheduled sweep whose ledger read fails") {
            fun ledgerThrowing(failure: Throwable): AgentAskTurnRepository =
                mockk {
                    every {
                        findResumable(pendingBefore = any(), orphanedBefore = any(), limit = any())
                    } throws failure
                }

            `when`("the read throws an exception") {
                val lane = lane(ledger = ledgerThrowing(failure = DataAccessResourceFailureException("ledger down")))
                val errors = loggedDuring(level = Level.ERROR) { lane.starter.resume() }

                then("the tick returns normally with the failure logged") {
                    errors shouldBe listOf("Ask turn resume sweep failed")
                }
            }

            `when`("the read throws an error") {
                val lane = lane(ledger = ledgerThrowing(failure = LinkageError("broken class")))

                then("the error is not swallowed") {
                    shouldThrow<LinkageError> { lane.starter.resume() }
                }
            }
        }

        given("a waiting turn whose sweep offer the executor rejects") {
            val lane = lane()
            lane.onOffer = { false }
            val eventId =
                storedTurn(
                    prompt = "waiting",
                    attemptCount = 2,
                    runCount = 1,
                    updatedAt = lane.clock.now.minus(gracePassed),
                )
            val before = turnOf(eventId = eventId)

            `when`("two sweeps find the executor full, a third finds room and a fourth runs while it is queued") {
                val rejected = listOf(lane.starter.resumeOnce(), lane.starter.resumeOnce())
                val afterRejections = turnOf(eventId = eventId)
                lane.onOffer = { true }
                val accepted = lane.starter.resumeOnce()
                val whileQueued = lane.starter.resumeOnce()

                then("a rejected offer writes nothing: the row keeps its status, counters and timestamps") {
                    rejected shouldContainExactly listOf(0, 0)
                    afterRejections.status shouldBe before.status
                    afterRejections.attemptCount shouldBe before.attemptCount
                    afterRejections.runCount shouldBe before.runCount
                    afterRejections.updatedAt shouldBe before.updatedAt
                    afterRejections.createdAt shouldBe before.createdAt
                }

                then("each sweep offers it again until one is accepted, and only that one is counted as resumed") {
                    accepted shouldBe 1
                    whileQueued shouldBe 0
                    lane.offered shouldHaveSize 3
                    lane.submitted.shouldBeEmpty()
                    lane.counted(outcome = "resumed") shouldBe 1.0
                }
            }
        }

        given("a sweep in which one row's question cannot be read") {
            val outbox = FlakyOutbox(delegate = outboxRepository, failures = 1)
            val lane = lane(outbox = outbox.repository)
            val now = lane.clock.now
            val unreadable =
                storedTurn(
                    prompt = "read fails",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = 1,
                    runCount = 1,
                    updatedAt = now.minusMinutes(10L),
                )
            storedTurn(prompt = "read works", updatedAt = now.minusMinutes(5L))

            `when`("the sweep runs") {
                val resumed = mutableListOf<Int>()
                val errors = loggedDuring(level = Level.ERROR) { resumed += lane.starter.resumeOnce() }

                then("the failing row is logged by its event id and the row after it is still offered") {
                    errors shouldBe listOf("Ask turn resume failed for eventId=$unreadable")
                    resumed shouldContainExactly listOf(1)
                    lane.offered.map { it.payload.prompt } shouldContainExactly listOf("read works")
                }

                then("the orphan released before the failure waits with a fresh timestamp") {
                    val released = turnOf(eventId = unreadable)
                    released.status shouldBe AgentAskTurnStatus.PENDING
                    released.attemptCount shouldBe 1
                    released.updatedAt shouldBe now
                }
            }

            `when`("the grace period passes and the sweep runs again") {
                lane.clock.advance(by = gracePassed)
                lane.starter.resumeOnce()

                then("the row that failed is offered this time") {
                    lane.offered.map { it.payload.prompt } shouldContainExactly listOf("read works", "read fails")
                }
            }
        }

        given("a sidecar request timeout longer than the default") {
            val lane =
                lane(
                    appConfig =
                        AppConfig(
                            agent = AppConfig.Agent(sidecar = AppConfig.Agent.Sidecar(requestTimeoutSeconds = 300L)),
                        ),
                )
            val cutoff =
                lane.clock.now
                    .minusSeconds(300L)
                    .minus(ASK_ORPHAN_MARGIN)
            storedTurn(
                prompt = "past the longer timeout",
                status = AgentAskTurnStatus.IN_PROGRESS,
                attemptCount = 1,
                updatedAt = cutoff.minusSeconds(1L),
            )
            val withinTimeout =
                storedTurn(
                    prompt = "within the longer timeout",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = 1,
                    updatedAt = cutoff.plusSeconds(1L),
                )

            `when`("the sweep runs") {
                lane.starter.resumeOnce()

                then("a run is released only once the configured timeout and the margin have both passed") {
                    lane.offered.map { it.payload.prompt } shouldContainExactly listOf("past the longer timeout")
                    turnOf(eventId = withinTimeout).status shouldBe AgentAskTurnStatus.IN_PROGRESS
                }
            }
        }

        given("turns whose cut-off runs reached the run budget") {
            val lane = lane()
            val now = lane.clock.now
            val touched = now.minus(gracePassed)
            val deliveredAt = now.minusMinutes(10L)
            val spentOrphan =
                storedTurn(
                    prompt = "spent orphan",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = ASK_MAX_RUNS,
                    runCount = ASK_MAX_RUNS,
                    createdAt = deliveredAt,
                    updatedAt = now.minusMinutes(5L),
                )
            val spent =
                storedTurn(
                    prompt = "spent",
                    attemptCount = ASK_MAX_RUNS + 4,
                    runCount = ASK_MAX_RUNS,
                    createdAt = deliveredAt,
                    updatedAt = touched,
                )
            val lastTry =
                storedTurn(
                    prompt = "last try",
                    attemptCount = ASK_MAX_RUNS + 4,
                    runCount = ASK_MAX_RUNS - 1,
                    createdAt = deliveredAt,
                    updatedAt = touched.plusNanos(1_000L),
                )

            `when`("the sweep runs") {
                val errors = loggedDuring(level = Level.ERROR) { lane.starter.resumeOnce() }

                then("both spent turns get the failure notice in their thread and are ABANDONED, counted and logged") {
                    lane.abandoned.map { it.payload.prompt } shouldContainExactly listOf("spent orphan", "spent")
                    lane.abandoned.map { it.payload.threadId }.distinct() shouldContainExactly listOf(TEST_MESSAGE_TS)
                    lane.abandoned.map { it.payload.responseBasicInfo }.distinct() shouldContainExactly
                        listOf(basicInfo)
                    turnOf(eventId = spentOrphan).status shouldBe AgentAskTurnStatus.ABANDONED
                    turnOf(eventId = spent).status shouldBe AgentAskTurnStatus.ABANDONED
                    lane.counted(outcome = "abandoned") shouldBe 2.0
                    errors shouldBe
                        listOf(spentOrphan, spent).map {
                            "Ask turn eventId=$it abandoned unanswered after $ASK_MAX_RUNS runs, " +
                                "delivered at $deliveredAt"
                        }
                }

                then("a turn one run short of the budget is offered again, however often it was begun") {
                    lane.offered.map { it.payload.prompt } shouldContainExactly listOf("last try")
                    turnOf(eventId = lastTry).status shouldBe AgentAskTurnStatus.PENDING
                }
            }
        }

        given("a turn the sidecar keeps reporting busy") {
            val lane = lane()
            val eventId = storedQuestion()
            lane.starter.onDelivered(event = deliveryOf(eventId = eventId))
            val busyRuns = ASK_MAX_RUNS + 2

            `when`("it is begun and given back more often than the run budget") {
                val owed =
                    (1..busyRuns).map {
                        lane.leases.last().begin()
                        val notice = lane.leases.last().giveBack()
                        lane.clock.advance(by = gracePassed)
                        lane.starter.resumeOnce()
                        notice
                    }
                val waiting = turnOf(eventId = eventId)

                then("only the first give-back owes the answer-later notice; every later one owes none") {
                    owed shouldContainExactly
                        listOf(BusyNotice.ANSWER_LATER) + List(size = busyRuns - 1) { BusyNotice.NONE }
                }

                then("every sweep offers it again: each give-back took its run back while the attempts kept rising") {
                    lane.offered shouldHaveSize busyRuns
                    lane.abandoned.shouldBeEmpty()
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe busyRuns
                    waiting.runCount shouldBe 0
                }
            }

            `when`("the give-up window passes") {
                lane.clock.advance(by = ASK_GIVE_UP_AFTER)
                lane.starter.resumeOnce()

                then("the window, not the budget, ends it with the failure notice") {
                    lane.abandoned shouldHaveSize 1
                    turnOf(eventId = eventId).status shouldBe AgentAskTurnStatus.ABANDONED
                    turnOf(eventId = eventId).runCount shouldBe 0
                }
            }
        }

        given("turns delivered around the give-up window") {
            val lane = lane()
            val now = lane.clock.now
            val touched = now.minus(gracePassed)
            val expiredWaiting =
                storedTurn(
                    prompt = "expired waiting",
                    attemptCount = 1,
                    createdAt = now.minus(ASK_GIVE_UP_AFTER).minusSeconds(1L),
                    updatedAt = touched,
                )
            val expiredOrphan =
                storedTurn(
                    prompt = "expired orphan",
                    status = AgentAskTurnStatus.IN_PROGRESS,
                    attemptCount = 1,
                    createdAt = now.minus(ASK_GIVE_UP_AFTER).minusSeconds(1L),
                    updatedAt = now.minusMinutes(30L),
                )
            storedTurn(
                prompt = "inside the window",
                attemptCount = 1,
                createdAt = now.minus(ASK_GIVE_UP_AFTER),
                updatedAt = touched.plusNanos(1_000L),
            )

            `when`("the sweep runs") {
                lane.starter.resumeOnce()

                then("both expired turns are ABANDONED with the failure notice, the orphan after its release") {
                    lane.abandoned.map { it.payload.prompt } shouldContainExactly
                        listOf("expired orphan", "expired waiting")
                    turnOf(eventId = expiredWaiting).status shouldBe AgentAskTurnStatus.ABANDONED
                    turnOf(eventId = expiredOrphan).status shouldBe AgentAskTurnStatus.ABANDONED
                    lane.counted(outcome = "abandoned") shouldBe 2.0
                }

                then("the turn delivered exactly at the window's edge is offered again") {
                    lane.offered.map { it.payload.prompt } shouldContainExactly listOf("inside the window")
                }
            }
        }

        given("a give-up whose failure notice cannot be written") {
            val lane = lane()
            lane.onAbandon = { false }
            val now = lane.clock.now
            val spent =
                storedTurn(prompt = "spent", runCount = ASK_MAX_RUNS, updatedAt = now.minus(gracePassed))

            `when`("the sweep runs") {
                val errors = loggedDuring(level = Level.ERROR) { lane.starter.resumeOnce() }

                then("the turn is left for the next sweep and is neither counted nor logged as abandoned") {
                    turnOf(eventId = spent).status shouldBe AgentAskTurnStatus.PENDING
                    lane.counted(outcome = "abandoned") shouldBe 0.0
                    errors.shouldBeEmpty()
                }
            }
        }

        given("a waiting turn swept while the executor is full, through the real service") {
            val runnable = mutableListOf<Runnable>()
            val room = AtomicBoolean(false)
            val real =
                realLane(
                    gateway = mockk(),
                    turnExecutor =
                        Executor { turn ->
                            if (!room.get()) throw RejectedExecutionException("full")
                            runnable += turn
                        },
                )
            val eventId =
                storedTurn(
                    prompt = "waiting",
                    attemptCount = 2,
                    runCount = 1,
                    updatedAt = real.clock.now.minus(gracePassed),
                )
            val before = jdbc.queryForMap("SELECT * FROM agent_ask_turn WHERE event_id = ?", eventId)

            `when`("the sweep's offer is rejected") {
                val resumed = real.starter.resumeOnce()
                val after = jdbc.queryForMap("SELECT * FROM agent_ask_turn WHERE event_id = ?", eventId)

                then("the row is exactly as it was and no overload notice is staged or published") {
                    resumed shouldBe 0
                    after shouldBe before
                    verify { listOf(real.outboundStager, real.eventPublisher) wasNot Called }
                }
            }

            `when`("a later sweep finds room") {
                room.set(true)
                val resumed = real.starter.resumeOnce()

                then("the same turn is handed to the executor") {
                    resumed shouldBe 1
                    runnable shouldHaveSize 1
                }
            }
        }

        given("an asked question the sidecar reports busy, through the real service and transaction manager") {
            val real = realLane(gateway = busyGateway(), turnExecutor = Executor { it.run() })
            val eventId = storedQuestion()

            `when`("the question is delivered and its first run is busy") {
                real.starter.onDelivered(event = deliveryOf(eventId = eventId))
                val waiting = turnOf(eventId = eventId)

                then("the give-back, one BUSY audit row and the answer-later notice are committed together") {
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe 1
                    waiting.runCount shouldBe 0
                    auditOutcomes() shouldContainExactly listOf(AgentTurnOutcome.BUSY.name)
                    real.committedNotices() shouldBe 1
                    real.staged
                        .single()
                        .shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AgentConverseService.ANSWER_LATER_MESSAGE
                }
            }

            `when`("the sweep runs it again after the grace period and the sidecar is still busy") {
                real.clock.advance(by = gracePassed)
                val resumed = real.starter.resumeOnce()
                val waiting = turnOf(eventId = eventId)

                then("the second run is given back quietly: no further audit row or notice") {
                    resumed shouldBe 1
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe 2
                    waiting.runCount shouldBe 0
                    auditOutcomes() shouldContainExactly listOf(AgentTurnOutcome.BUSY.name)
                    real.committedNotices() shouldBe 1
                    real.staged shouldHaveSize 1
                }
            }
        }

        given("a first busy run whose notice cannot be written, through the real service and transaction manager") {
            val real = realLane(gateway = busyGateway(), turnExecutor = Executor { it.run() })
            real.noticeFails = true
            val eventId = storedQuestion()

            `when`("the question is delivered") {
                real.starter.onDelivered(event = deliveryOf(eventId = eventId))
                val running = turnOf(eventId = eventId)

                then("the give-back and the audit row roll back with it; the run stays IN_PROGRESS for the sweep") {
                    running.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    running.attemptCount shouldBe 1
                    running.runCount shouldBe 1
                    auditOutcomes().shouldBeEmpty()
                    real.committedNotices() shouldBe 0
                }
            }

            `when`("the notice can be written again and the sweep reruns the orphaned run, still busy") {
                real.noticeFails = false
                real.clock.advance(
                    by =
                        Duration
                            .ofSeconds(AppConfig().agent.sidecar.requestTimeoutSeconds)
                            .plus(ASK_ORPHAN_MARGIN)
                            .plusSeconds(1L),
                )
                val resumed = real.starter.resumeOnce()
                val waiting = turnOf(eventId = eventId)

                then("that run is a later one: it is given back with no notice, so none is sent twice or at all") {
                    resumed shouldBe 1
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe 2
                    waiting.runCount shouldBe 1
                    auditOutcomes().shouldBeEmpty()
                    real.committedNotices() shouldBe 0
                    real.staged shouldHaveSize 1
                }
            }
        }

        given("a question whose first run was busy and whose later offers are rejected until the window ends") {
            val room = AtomicBoolean(true)
            val real =
                realLane(
                    gateway = busyGateway(),
                    turnExecutor =
                        Executor { turn ->
                            if (!room.get()) throw RejectedExecutionException("full")
                            turn.run()
                        },
                )
            val eventId = storedQuestion()
            real.starter.onDelivered(event = deliveryOf(eventId = eventId))
            room.set(false)

            `when`("sweeps find the executor full and then the give-up window passes") {
                real.clock.advance(by = gracePassed)
                val rejected = listOf(real.starter.resumeOnce(), real.starter.resumeOnce())
                val waiting = turnOf(eventId = eventId)
                real.clock.advance(by = ASK_GIVE_UP_AFTER)
                real.starter.resumeOnce()

                then("the rejected offers add nothing: the turn still waits on its first attempt") {
                    rejected shouldContainExactly listOf(0, 0)
                    waiting.status shouldBe AgentAskTurnStatus.PENDING
                    waiting.attemptCount shouldBe 1
                    waiting.runCount shouldBe 0
                }

                then("the requester got the busy notice once and then exactly one failure notice from the give-up") {
                    turnOf(eventId = eventId).status shouldBe AgentAskTurnStatus.ABANDONED
                    real.committedNotices() shouldBe 2
                    real.staged.map { it::class } shouldContainExactly
                        listOf(OutboundMessage.Ephemeral::class, OutboundMessage.ChannelMessage::class)
                    real.staged
                        .last()
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AgentConverseService.FAILURE_MESSAGE
                    auditOutcomes() shouldContainExactly listOf(AgentTurnOutcome.BUSY.name)
                }
            }
        }

        given("a question delivered again on a full instance while another instance has its turn queued") {
            val queue = QueuedExecutor()
            val answering =
                mockk<AgentGateway> {
                    every { converse(request = any()) } returns
                        AgentTurnResult.Completed(sessionId = null, finalText = "answered once")
                }
            val crashed = realLane(gateway = mockk(), turnExecutor = QueuedExecutor())
            val sweeping = RealServiceLane(gateway = answering, turnExecutor = queue)
            val restarted =
                RealServiceLane(gateway = mockk(), turnExecutor = Executor { throw RejectedExecutionException("full") })
            val eventId = storedQuestion()
            crashed.starter.onDelivered(event = deliveryOf(eventId = eventId))
            sweeping.clock.advance(by = gracePassed)
            val queuedElsewhere = sweeping.starter.resumeOnce()
            val before = jdbc.queryForMap("SELECT * FROM agent_ask_turn WHERE event_id = ?", eventId)

            `when`("the outbox recovery delivers the question again and the hand-off is rejected") {
                restarted.starter.onDelivered(event = deliveryOf(eventId = eventId))
                val after = jdbc.queryForMap("SELECT * FROM agent_ask_turn WHERE event_id = ?", eventId)

                then("nothing is written: the row is as the other instance left it and no overload notice is staged") {
                    queuedElsewhere shouldBe 1
                    after shouldBe before
                    verify { listOf(restarted.outboundStager, restarted.eventPublisher) wasNot Called }
                }
            }

            `when`("the other instance's queued turn starts") {
                queue.runAll()
                val answered = turnOf(eventId = eventId)

                then("it still wins the turn and answers in the question's thread") {
                    answered.status shouldBe AgentAskTurnStatus.COMPLETED
                    answered.attemptCount shouldBe 1
                    val answer = sweeping.staged.single().shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    answer.threadId shouldBe TEST_MESSAGE_TS
                    answer.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe "answered once"
                    sweeping.committedNotices() shouldBe 1
                    auditOutcomes() shouldContainExactly listOf(AgentTurnOutcome.COMPLETED.name)
                }
            }
        }
    })
