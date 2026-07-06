package dev.notypie.application.service.relay

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.OutboundRenderer
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime

class OutboxPayloadRendererTest :
    BehaviorSpec({
        val port = CodecOutboundMessagePort()
        val basicInfo = createCommandBasicInfo()
        val message =
            OutboundMessage.ChannelMessage(
                target = ConversationTarget(id = basicInfo.channel),
                content = MessageContent.Text(headline = "hi", markdown = "hello"),
            )

        given("a row with a registered transport renderer") {
            val rendered = mockk<SlackEventPayload>()
            val slackRenderer = mockk<OutboundRenderer>()
            every { slackRenderer.render(message = any(), basicInfo = any()) } returns rendered
            val payloadRenderer = OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to slackRenderer))
            val row = port.toRow(message = message, basicInfo = basicInfo)

            `when`("the row is rendered at deliver time") {
                val result = payloadRenderer.render(row = row)

                then("the envelope is decoded and handed to the transport's renderer") {
                    result shouldBe rendered
                    verify(exactly = 1) {
                        slackRenderer.render(
                            message = message,
                            basicInfo = basicInfo,
                        )
                    }
                }
            }
        }

        given("a row whose schema version this binary cannot decode") {
            val payloadRenderer =
                OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to mockk<OutboundRenderer>()))
            val futureRow =
                OutboxMessage(
                    eventId = "e1",
                    idempotencyKey = basicInfo.idempotencyKey.toString(),
                    publisherId = basicInfo.publisherId,
                    transport = Transport.SLACK.name,
                    payload = "{}",
                    createdAt = LocalDateTime.now(),
                    schemaVersion = 9999,
                )

            `when`("render is called") {
                then("it refuses with a clear unsupported-version error before decoding") {
                    val ex = shouldThrow<IllegalArgumentException> { payloadRenderer.render(row = futureRow) }
                    require(ex.message!!.contains("Unsupported outbox schemaVersion=9999"))
                    require(ex.message!!.contains("Refusing to dispatch"))
                }
            }
        }

        given("a row for a transport with no registered renderer") {
            val payloadRenderer = OutboxPayloadRenderer(renderers = emptyMap())
            val row = port.toRow(message = message, basicInfo = basicInfo)

            `when`("render is called") {
                then("it fails loudly rather than silently dropping the row") {
                    val ex = shouldThrow<IllegalStateException> { payloadRenderer.render(row = row) }
                    require(ex.message!!.contains("No renderer registered for transport=SLACK"))
                }
            }
        }
    })
