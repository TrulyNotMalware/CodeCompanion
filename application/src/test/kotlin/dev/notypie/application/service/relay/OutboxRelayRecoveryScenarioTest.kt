package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.MutableClock
import dev.notypie.application.outbox.QueuedExecutor
import dev.notypie.application.outbox.ScriptedMessageDispatcher
import dev.notypie.application.outbox.createOutboxJpaContext
import dev.notypie.application.outbox.createPollingProcessorFixture
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.outboxColumn
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.impl.command.RateLimitedOutput
import dev.notypie.impl.command.TRANSIENT_EXHAUSTED_REASON
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.command.event.failOutput
import dev.notypie.impl.command.event.successOutput
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.schema.createOutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.concurrent.Executor

class OutboxRelayRecoveryScenarioTest :
    BehaviorSpec({
        val context = createOutboxJpaContext()
        afterSpec { context.close() }
        val repository = context.getBean(MessageOutboxRepository::class.java)
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val payload = createPostEventPayloadContents(commandDetailType = CommandDetailType.SIMPLE_TEXT)
        val renderer =
            mockk<OutboxPayloadRenderer> {
                every { render(row = any()) } returns
                    RenderedRow(payload = payload, next = null)
            }

        val delivered: (SlackEventPayload) -> CommandOutput =
            { successOutput(payload = it, commandType = CommandType.EXTERNAL_API) }
        val transientExhausted: (SlackEventPayload) -> CommandOutput =
            { failOutput(event = it, reason = TRANSIENT_EXHAUSTED_REASON) }
        val rateLimited: (SlackEventPayload) -> CommandOutput =
            { RateLimitedOutput(event = it, retryAfter = Duration.ofSeconds(30L)) }

        fun statusOf(eventId: String) = jdbc.outboxColumn(eventId = eventId, column = "status")

        fun sendsOf(eventId: String) = jdbc.outboxColumn(eventId = eventId, column = "send_count")

        fun claimsOf(eventId: String) = jdbc.outboxColumn(eventId = eventId, column = "attempt_count")

        class Lane(
            clock: MutableClock,
            dispatcher: ScriptedMessageDispatcher,
            appConfig: AppConfig = AppConfig(),
            executor: Executor = Executor { it.run() },
        ) {
            val relay =
                createRelayService(
                    outboxRepository = repository,
                    payloadRenderer = renderer,
                    messageDispatcher = dispatcher,
                    clock = clock,
                    relayTaskExecutor = executor,
                    appConfig = appConfig,
                )
            val poller =
                createPollingProcessorFixture(outboxRepository = repository, relayService = relay, clock = clock)
                    .processor
            val sweeper =
                OutboxRecoveryScheduler(
                    outboxRepository = repository,
                    messageRelayService = relay,
                    appConfig = appConfig,
                    clock = clock,
                )
        }

        given("a message Slack keeps rate-limiting for longer than the send budget") {
            jdbc.update("DELETE FROM outbox_message")
            val clock = MutableClock()
            val dispatcher = ScriptedMessageDispatcher(outcomes = List(size = 16) { rateLimited }, fallback = delivered)
            val lane = Lane(clock = clock, dispatcher = dispatcher)
            val eventId = repository.save(createOutboxMessage(createdAt = clock.now)).eventId

            `when`("it is sent, swept before Retry-After, then swept fifteen times into 429s, then Slack recovers") {
                lane.poller.pollPending()
                clock.advance(by = Duration.ofSeconds(20L))
                lane.sweeper.recoverOnce()
                val callsBeforeRetryAfter = dispatcher.calls
                repeat(times = 15) {
                    clock.advance(by = Duration.ofMinutes(5L))
                    lane.sweeper.recoverOnce()
                }
                val statusWhileLimited = statusOf(eventId = eventId)
                val sendsWhileLimited = sendsOf(eventId = eventId)
                val callsWhileLimited = dispatcher.calls
                clock.advance(by = Duration.ofMinutes(5L))
                lane.sweeper.recoverOnce()

                then("it is not re-sent before Retry-After, never abandoned, and delivered once Slack recovers") {
                    callsBeforeRetryAfter shouldBe 1
                    callsWhileLimited shouldBe 16
                    statusWhileLimited shouldBe MessageStatus.IN_PROGRESS.name
                    sendsWhileLimited shouldBe 0
                    dispatcher.calls shouldBe 17
                    statusOf(eventId = eventId) shouldBe MessageStatus.SUCCESS.name
                    claimsOf(eventId = eventId) shouldBe 17
                }
            }
        }

        given("a message whose sends keep failing transiently") {
            jdbc.update("DELETE FROM outbox_message")
            val clock = MutableClock()
            val dispatcher = ScriptedMessageDispatcher(outcomes = emptyList(), fallback = transientExhausted)
            val lane =
                Lane(
                    clock = clock,
                    dispatcher = dispatcher,
                    appConfig =
                        AppConfig(outbox = AppConfig.Outbox(polling = AppConfig.Outbox.Polling(maxSends = 3))),
                )
            val eventId = repository.save(createOutboxMessage(createdAt = clock.now)).eventId

            `when`("the poller sends it and five recovery sweeps follow") {
                lane.poller.pollPending()
                repeat(times = 5) {
                    clock.advance(by = Duration.ofMinutes(6L))
                    lane.sweeper.recoverOnce()
                }

                then("it is re-sent until the send budget is spent and then abandoned to FAILURE") {
                    dispatcher.calls shouldBe 3
                    sendsOf(eventId = eventId) shouldBe 3
                    statusOf(eventId = eventId) shouldBe MessageStatus.FAILURE.name
                }
            }
        }

        given("PENDING rows that outlived the give-up window while the whole app was down") {
            jdbc.update("DELETE FROM outbox_message")
            val clock = MutableClock()
            val dispatcher = ScriptedMessageDispatcher(outcomes = emptyList(), fallback = delivered)
            val lane = Lane(clock = clock, dispatcher = dispatcher)
            val sweptId = repository.save(createOutboxMessage(createdAt = clock.now)).eventId
            clock.advance(by = Duration.ofHours(25L))

            `when`("the recovery sweep runs first, then a new row arrives and the poller runs") {
                lane.sweeper.recoverOnce()
                val sweptStatus = statusOf(eventId = sweptId)
                val polledId = repository.save(createOutboxMessage(createdAt = clock.now)).eventId
                clock.advance(by = Duration.ofHours(25L))
                lane.poller.pollPending()

                then("neither is sent: both end as FAILURE, the swept one without ever being claimed") {
                    dispatcher.calls shouldBe 0
                    sweptStatus shouldBe MessageStatus.FAILURE.name
                    claimsOf(eventId = sweptId) shouldBe 0
                    statusOf(eventId = polledId) shouldBe MessageStatus.FAILURE.name
                    sendsOf(eventId = polledId) shouldBe 0
                }
            }
        }

        given("a row a newer release wrote in a schema version this binary cannot read") {
            jdbc.update("DELETE FROM outbox_message")
            val clock = MutableClock()
            val dispatcher = ScriptedMessageDispatcher(outcomes = emptyList(), fallback = delivered)
            val lane = Lane(clock = clock, dispatcher = dispatcher)
            val eventId = repository.save(createOutboxMessage(createdAt = clock.now)).eventId
            jdbc.update("UPDATE outbox_message SET schema_version = 9999 WHERE event_id = ?", eventId)

            `when`("the poller claims it and two recovery sweeps reclaim it") {
                lane.poller.pollPending()
                repeat(times = 2) {
                    clock.advance(by = Duration.ofMinutes(6L))
                    lane.sweeper.recoverOnce()
                }

                then("it is never sent and never failed, and spends none of the send budget") {
                    dispatcher.calls shouldBe 0
                    statusOf(eventId = eventId) shouldBe MessageStatus.IN_PROGRESS.name
                    sendsOf(eventId = eventId) shouldBe 0
                    claimsOf(eventId = eventId) shouldBe 3
                }
            }
        }

        given("a claim that waits in the relay queue past the stuck threshold") {
            jdbc.update("DELETE FROM outbox_message")
            val clock = MutableClock()
            val executor = QueuedExecutor()
            val dispatcher = ScriptedMessageDispatcher(outcomes = emptyList(), fallback = delivered)
            val lane = Lane(clock = clock, dispatcher = dispatcher, executor = executor)
            val eventId = repository.save(createOutboxMessage(createdAt = clock.now)).eventId

            `when`("the sweep takes it over and both queued tasks then run") {
                lane.poller.pollPending()
                clock.advance(by = Duration.ofMinutes(6L))
                lane.sweeper.recoverOnce()
                val queued = executor.size
                executor.runAll()

                then("only the new owner sends, and the taken-over claim spends none of the budget") {
                    queued shouldBe 2
                    dispatcher.calls shouldBe 1
                    sendsOf(eventId = eventId) shouldBe 1
                    claimsOf(eventId = eventId) shouldBe 2
                    statusOf(eventId = eventId) shouldBe MessageStatus.SUCCESS.name
                }
            }
        }
    })
