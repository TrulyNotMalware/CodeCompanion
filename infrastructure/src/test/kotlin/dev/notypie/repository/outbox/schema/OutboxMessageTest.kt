package dev.notypie.repository.outbox.schema

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.Transport
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf

class OutboxMessageTest :
    BehaviorSpec({
        val port = CodecOutboundMessagePort()

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
                    row.schemaVersion shouldBe OutboxSchemaVersion.CURRENT
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
