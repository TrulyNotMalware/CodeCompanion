package dev.notypie.application.service.relay

import dev.notypie.application.outbox.MutableClock
import dev.notypie.application.outbox.ScriptedMessageDispatcher
import dev.notypie.application.outbox.createOutboxJpaContext
import dev.notypie.application.outbox.createPollingProcessorFixture
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.outboxColumn
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.OutboundRenderer
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.command.event.successOutput
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import dev.notypie.repository.outbox.toChainHead
import dev.notypie.schema.createOutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OutboxChainJpaTransactionTest :
    BehaviorSpec({
        val context = createOutboxJpaContext()
        afterSpec { context.close() }
        val repository = context.getBean(MessageOutboxRepository::class.java)
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val entityManagerFactory = context.getBean(EntityManagerFactory::class.java)
        val codecPort = CodecOutboundMessagePort()
        val basicInfo = createCommandBasicInfo()
        val parts =
            (1..3).map { index ->
                OutboundMessage.ChannelMessage(
                    target = ConversationTarget(id = basicInfo.channel),
                    content = MessageContent.Text(headline = "($index/3)", markdown = "part $index"),
                )
            }
        val delivered: (SlackEventPayload) -> CommandOutput =
            { successOutput(payload = it, commandType = CommandType.EXTERNAL_API) }

        class Lane(
            port: OutboundMessagePort = codecPort,
            transactionManager: PlatformTransactionManager = context.getBean(JpaTransactionManager::class.java),
        ) {
            val clock = MutableClock()
            val sent = CopyOnWriteArrayList<OutboundMessage>()
            val dispatcher = ScriptedMessageDispatcher(outcomes = emptyList(), fallback = delivered)
            private val renderer =
                mockk<OutboundRenderer> {
                    every { render(message = any(), basicInfo = any()) } answers {
                        sent += firstArg<OutboundMessage>()
                        createPostEventPayloadContents(commandDetailType = CommandDetailType.SIMPLE_TEXT)
                    }
                }
            val relay =
                createRelayService(
                    outboxRepository = repository,
                    outboundMessagePort = port,
                    payloadRenderer = OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to renderer)),
                    messageDispatcher = dispatcher,
                    clock = clock,
                    transactionManager = transactionManager,
                )
            val poller =
                createPollingProcessorFixture(outboxRepository = repository, relayService = relay, clock = clock)
                    .processor
            val cdc = DebeziumLogTailingProcessor(outboxRepository = repository, relayService = relay, clock = clock)
        }

        fun statuses(): List<String> =
            jdbc.queryForList("SELECT status FROM outbox_message", String::class.java).filterNotNull()

        fun pendingIds(): List<String> =
            jdbc
                .queryForList("SELECT event_id FROM outbox_message WHERE status = 'PENDING'", String::class.java)
                .filterNotNull()

        fun payloadOf(eventId: String): String = jdbc.outboxColumn(eventId = eventId, column = "payload") as String

        fun stageHead(): String =
            repository.save(codecPort.toChainHead(messages = parts, basicInfo = basicInfo)).eventId

        given("a three-part chain relayed by the poller") {
            jdbc.update("DELETE FROM outbox_message")
            val lane = Lane()
            val headId = stageHead()

            `when`("the poller runs once, then twice more") {
                lane.poller.pollPending()
                val afterFirst = pendingIds()
                val secondPayload = OutboundMessageCodec.decode(json = payloadOf(eventId = afterFirst.single()))
                lane.poller.pollPending()
                lane.poller.pollPending()

                then("the first SUCCESS commits with exactly one PENDING row that carries the second part") {
                    jdbc.outboxColumn(eventId = headId, column = "status") shouldBe MessageStatus.SUCCESS.name
                    jdbc.outboxColumn(eventId = headId, column = "schema_version") shouldBe OutboxSchemaVersion.V3
                    secondPayload.message shouldBe parts[1]
                    secondPayload.continuation shouldBe listOf(parts[2])
                }

                then("the parts are sent one after another in order, each once, and the chain ends") {
                    lane.sent shouldBe parts
                    statuses() shouldBe List(size = 3) { MessageStatus.SUCCESS.name }
                    pendingIds() shouldBe emptyList()
                }
            }
        }

        given("a three-part chain relayed from CDC change events") {
            jdbc.update("DELETE FROM outbox_message")
            val lane = Lane()
            val headId = stageHead()

            `when`("each row's insert event is consumed, and the head's event is redelivered at the end") {
                lane.cdc.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = headId)))
                repeat(times = 2) {
                    val nextId = pendingIds().single()
                    lane.cdc.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = nextId)))
                }
                lane.cdc.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = headId)))

                then("the parts are sent in order, and the redelivered head neither resends nor restages") {
                    lane.sent shouldBe parts
                    statuses() shouldBe List(size = 3) { MessageStatus.SUCCESS.name }
                    pendingIds() shouldBe emptyList()
                }
            }
        }

        given("a chain whose next row cannot be inserted") {
            jdbc.update("DELETE FROM outbox_message")
            val occupiedId = repository.save(createOutboxMessage(status = MessageStatus.SUCCESS)).eventId
            val collidingPort =
                mockk<OutboundMessagePort> {
                    every { toRow(message = any(), basicInfo = any(), transport = any(), continuation = any()) } answers
                        { createOutboxMessage(eventId = occupiedId) }
                }
            val lane = Lane(port = collidingPort)
            val headId = stageHead()

            `when`("the poller sends the first part") {
                lane.poller.pollPending()

                then("SUCCESS rolls back with the failed insert, so the row is left for the sweep, not lost") {
                    lane.dispatcher.calls shouldBe 1
                    jdbc.outboxColumn(eventId = headId, column = "status") shouldBe MessageStatus.IN_PROGRESS.name
                    statuses().size shouldBe 2
                }
            }
        }

        given("a SUCCESS write whose commit lands but whose acknowledgement is lost") {
            jdbc.update("DELETE FROM outbox_message")
            val lostAcknowledgements = AtomicInteger(1)
            val transactionManager =
                object : JpaTransactionManager(entityManagerFactory) {
                    override fun doCommit(status: DefaultTransactionStatus) {
                        super.doCommit(status)
                        if (lostAcknowledgements.getAndDecrement() > 0) {
                            throw TransactionSystemException("commit acknowledgement lost")
                        }
                    }
                }
            val lane = Lane(transactionManager = transactionManager)
            val headId = stageHead()

            `when`("the poller sends the first part and the write is retried") {
                lane.poller.pollPending()

                then("the retry finds the row recorded, and the second part is staged once") {
                    jdbc.outboxColumn(eventId = headId, column = "status") shouldBe MessageStatus.SUCCESS.name
                    pendingIds().size shouldBe 1
                    statuses().size shouldBe 2
                }
            }
        }
    })
