package dev.notypie.application.service.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createAgentQuestionMessage
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.UUID

private class StarterHarness {
    val outboxRepository: MessageOutboxRepository = mockk()
    private val eventPublisher: EventPublisher = mockk()
    private val queues = mutableListOf<EventQueue<CommandEvent<EventPayload>>>()
    val starter = AgentAskThreadStarter(outboxRepository = outboxRepository, eventPublisher = eventPublisher)

    init {
        every { eventPublisher.publishEvent(events = capture(queues)) } just Runs
    }

    fun store(row: OutboxMessage) {
        every { outboxRepository.findById(row.eventId) } returns Optional.of(row)
    }

    fun published(): List<CommandEvent<EventPayload>> = queues.flatMap { queue -> queue.snapshot() }
}

class AgentAskThreadStarterTest :
    BehaviorSpec({
        val basicInfo = createCommandBasicInfo()

        fun rowOf(message: OutboundMessage): OutboxMessage =
            CodecOutboundMessagePort().toRow(message = message, basicInfo = basicInfo)

        fun deliveryOf(
            eventId: String,
            commandDetailType: CommandDetailType = CommandDetailType.AGENT_ASK_QUESTION,
            messageTs: String = TEST_MESSAGE_TS,
        ): MessagePublishSuccessEvent =
            MessagePublishSuccessEvent(
                eventId = UUID.fromString(eventId),
                commandDetailType = commandDetailType,
                messageTs = messageTs,
            )

        fun warningsDuring(action: () -> Unit): List<String> {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(AgentAskThreadStarter::class.java.packageName) as Logger
            logger.addAppender(appender)
            try {
                action()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        }

        given("a delivered message that is not an asked question") {
            `when`("a standup summary is delivered") {
                val harness = StarterHarness()
                harness.starter.onDelivered(
                    event =
                        deliveryOf(
                            eventId = UUID.randomUUID().toString(),
                            commandDetailType = CommandDetailType.STANDUP_SUMMARY,
                        ),
                )

                then("the outbox is not read and no turn starts") {
                    verify(exactly = 0) { harness.outboxRepository.findById(any()) }
                    harness.published().shouldBeEmpty()
                }
            }
        }

        given("an asked question delivered without a Slack ts") {
            `when`("the success event carries a blank ts") {
                val harness = StarterHarness()
                harness.starter.onDelivered(event = deliveryOf(eventId = UUID.randomUUID().toString(), messageTs = ""))

                then("there is no thread to answer in, so the outbox is not read and no turn starts") {
                    verify(exactly = 0) { harness.outboxRepository.findById(any()) }
                    harness.published().shouldBeEmpty()
                }
            }
        }

        given("an asked question whose outbox row is gone") {
            `when`("the success event arrives") {
                val harness = StarterHarness()
                val eventId = UUID.randomUUID().toString()
                every { harness.outboxRepository.findById(eventId) } returns Optional.empty()

                val warnings = warningsDuring { harness.starter.onDelivered(event = deliveryOf(eventId = eventId)) }

                then("a warning names the event and no turn starts") {
                    warnings shouldBe listOf("Asked question row is gone; no answer thread starts for eventId=$eventId")
                    harness.published().shouldBeEmpty()
                }
            }
        }

        given("an asked question whose outbox row carries the question") {
            val row = rowOf(message = createAgentQuestionMessage(prompt = "why is CI red?"))

            `when`("the success event arrives") {
                val harness = StarterHarness()
                harness.store(row = row)
                harness.starter.onDelivered(event = deliveryOf(eventId = row.eventId))

                then("exactly one AgentConverse request starts a turn threaded under the posted question") {
                    val request = harness.published().single().shouldBeInstanceOf<AgentConverseRequestEvent>()
                    request.idempotencyKey shouldBe basicInfo.idempotencyKey
                    request.type shouldBe CommandDetailType.AGENT_CONVERSE
                    request.payload.prompt shouldBe "why is CI red?"
                    request.payload.threadId shouldBe TEST_MESSAGE_TS
                    request.payload.requesterName shouldBe TEST_USER_NAME
                    request.payload.channelName shouldBe TEST_CHANNEL_NAME
                    request.payload.responseBasicInfo shouldBe basicInfo
                }
            }

            `when`("the listener is handed the same success event twice") {
                val harness = StarterHarness()
                harness.store(row = row)
                repeat(times = 2) { harness.starter.onDelivered(event = deliveryOf(eventId = row.eventId)) }

                then("each call starts its own turn, because the listener keeps no record of handled events") {
                    harness.published() shouldHaveSize 2
                }
            }
        }

        given("a row tagged as an asked question that carries other content") {
            val row =
                rowOf(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = basicInfo.channel),
                            content = MessageContent.Text(headline = null, markdown = "plain"),
                            detailType = CommandDetailType.AGENT_ASK_QUESTION,
                        ),
                )

            `when`("the success event arrives") {
                val harness = StarterHarness()
                harness.store(row = row)

                val warnings = warningsDuring { harness.starter.onDelivered(event = deliveryOf(eventId = row.eventId)) }

                then("a warning names the row and no turn starts") {
                    warnings shouldBe
                        listOf("Row eventId=${row.eventId} is tagged as an asked question but carries none; skipping")
                    harness.published().shouldBeEmpty()
                }
            }
        }
    })
