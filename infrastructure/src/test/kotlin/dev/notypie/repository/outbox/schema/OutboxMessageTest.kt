package dev.notypie.repository.outbox.schema

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.chainedParts
import dev.notypie.repository.outbox.toChainHead
import dev.notypie.schema.createOutboxColumnMap
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

class OutboxMessageTest :
    BehaviorSpec({
        val port = CodecOutboundMessagePort()

        given("a CDC after-image on a JVM whose default zone is not UTC") {
            val previousZone = TimeZone.getDefault()
            val expected = LocalDateTime.of(2026, 10, 1, 12, 34, 56, 123_456_000)
            val epochMicros = expected.toEpochSecond(ZoneOffset.UTC) * 1_000_000 + 123_456
            val epochMillis = expected.toEpochSecond(ZoneOffset.UTC) * 1_000 + 123

            `when`("created_at arrives as Debezium micros and updated_at as Debezium millis") {
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
                val converted =
                    try {
                        createOutboxColumnMap(createdAt = epochMicros, updatedAt = epochMillis).toOutboxMessage()
                    } finally {
                        TimeZone.setDefault(previousZone)
                    }

                then("both read back as the stored wall-clock value, not shifted by the JVM zone") {
                    converted.createdAt shouldBe expected
                    converted.updatedAt shouldBe expected.withNano(123_000_000)
                }
            }
        }

        given("CodecOutboundMessagePort.toRow") {
            val basicInfo = createCommandBasicInfo()
            val message =
                OutboundMessage.ChannelMessage(
                    target = ConversationTarget(id = basicInfo.channel),
                    content = MessageContent.Text(headline = "hi", markdown = "hello world"),
                )

            `when`("a row is built from a message and its command context") {
                val row = port.toRow(message = message, basicInfo = basicInfo)

                then("identity and routing columns come from the command context") {
                    row.eventId.shouldNotBeBlank()
                    row.idempotencyKey shouldBe basicInfo.idempotencyKey.toString()
                    row.publisherId shouldBe basicInfo.publisherId
                    row.transport shouldBe Transport.SLACK.name
                }

                then("the row is stamped at the current schema version and starts PENDING") {
                    row.schemaVersion shouldBe OutboxSchemaVersion.V2
                    row.status shouldBe MessageStatus.PENDING.name
                }

                then("each row mints its own event id") {
                    val other = port.toRow(message = message, basicInfo = basicInfo)
                    (row.eventId == other.eventId) shouldBe false
                }

                then("the payload column round-trips through the codec back to the original envelope") {
                    val decoded = OutboundMessageCodec.decode(json = row.payload)
                    decoded.basicInfo shouldBe basicInfo
                    val channelMessage = decoded.message.shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    val text = channelMessage.content.shouldBeInstanceOf<MessageContent.Text>()
                    text.headline shouldBe "hi"
                    text.markdown shouldBe "hello world"
                }
            }
        }

        given("CodecOutboundMessagePort.toRow with the rest of a chain") {
            val basicInfo = createCommandBasicInfo()
            val parts =
                (1..3).map { index ->
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = basicInfo.channel),
                        content = MessageContent.Text(headline = "($index/3)", markdown = "part $index"),
                    )
                }

            `when`("the head row is built") {
                val head = port.toChainHead(messages = parts, basicInfo = basicInfo)
                val single = port.toChainHead(messages = parts.take(n = 1), basicInfo = basicInfo)

                then("it carries the first part with the rest queued in order behind it") {
                    val decoded = OutboundMessageCodec.decode(json = head.payload)
                    decoded.message shouldBe parts[0]
                    decoded.continuation shouldBe parts.drop(n = 1)
                    head.chainedParts() shouldBe 2
                }

                then(
                    "only a row with a continuation is stamped V3, so an older binary holds it instead of sending part 1",
                ) {
                    head.schemaVersion shouldBe OutboxSchemaVersion.V3
                    single.schemaVersion shouldBe OutboxSchemaVersion.V2
                    single.chainedParts() shouldBe 0
                }
            }
        }

        given("CodecOutboundMessagePort.toRow for a replace-original message") {
            val basicInfo = createCommandBasicInfo()
            val fallback =
                OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = basicInfo.channel),
                    recipient = UserRef(id = basicInfo.publisherId),
                    content = MessageContent.Text(headline = null, markdown = "Meeting canceled."),
                )

            fun replace(withFallback: OutboundMessage.Ephemeral?) =
                port.toRow(
                    message =
                        OutboundMessage.ReplaceMessage(
                            handle = ResponseReplaceHandle(raw = "https://hooks.slack.com/actions/T1/1/list"),
                            content = MessageContent.Text(headline = null, markdown = "*Sync* canceled."),
                            fallback = withFallback,
                        ),
                    basicInfo = basicInfo,
                )

            `when`("the rows are built with and without a fallback") {
                val withFallback = replace(withFallback = fallback)
                val withoutFallback = replace(withFallback = null)

                then("only the row with a fallback is stamped V4, so an older binary holds it instead of dropping it") {
                    withFallback.schemaVersion shouldBe OutboxSchemaVersion.V4
                    withoutFallback.schemaVersion shouldBe OutboxSchemaVersion.V2
                    OutboxSchemaVersion.SUPPORTED shouldBe setOf(2, 3, 4)
                }

                then("the fallback survives the codec") {
                    OutboundMessageCodec
                        .decode(json = withFallback.payload)
                        .message
                        .shouldBeInstanceOf<OutboundMessage.ReplaceMessage>()
                        .fallback shouldBe fallback
                }
            }
        }

        given("OutboxMessage.updateMessageStatus") {
            val row =
                port.toRow(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = "C1"),
                            content = MessageContent.Text(headline = null, markdown = "body"),
                        ),
                    basicInfo = createCommandBasicInfo(),
                )

            `when`("status is updated to SUCCESS") {
                row.updateMessageStatus(status = MessageStatus.SUCCESS)

                then("status should be SUCCESS") {
                    row.status shouldBe MessageStatus.SUCCESS.name
                }
            }

            `when`("status is updated to FAILURE") {
                row.updateMessageStatus(status = MessageStatus.FAILURE)

                then("status should be FAILURE") {
                    row.status shouldBe MessageStatus.FAILURE.name
                }
            }
        }
    })
