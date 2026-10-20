package dev.notypie.application.service.relay

import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.command.createAgentQuestionMessage
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.DirectReply
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.outbound.askRefusedNotice
import dev.notypie.domain.command.outbound.directFallbackOf
import dev.notypie.impl.command.OutboundRenderer
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.SlackOutboundRenderer
import dev.notypie.impl.command.event.PostEventPayloadContents
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.OutboundEnvelope
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime

private const val ASK_PROMPT: String = "why is CI red?"

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
                    result shouldBe RenderedRow(payload = rendered, next = null)
                    verify(exactly = 1) {
                        slackRenderer.render(
                            message = message,
                            basicInfo = basicInfo,
                        )
                    }
                }
            }
        }

        given("a replace-original row that carries a fallback ephemeral") {
            val rendered = mockk<SlackEventPayload>()
            val slackRenderer = mockk<OutboundRenderer>()
            every { slackRenderer.render(message = any(), basicInfo = any()) } returns rendered
            val fallback =
                OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = basicInfo.channel),
                    content = MessageContent.Text(headline = null, markdown = "Meeting canceled."),
                )
            val replace =
                OutboundMessage.ReplaceMessage(
                    handle = ResponseReplaceHandle(raw = "https://hooks.slack.com/actions/T1/1/list"),
                    content = MessageContent.Text(headline = null, markdown = "*Sync* canceled."),
                    fallback = fallback,
                )

            `when`("the row is rendered") {
                val result =
                    OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to slackRenderer))
                        .render(row = port.toRow(message = replace, basicInfo = basicInfo))

                then("the replacement is rendered, and the fallback is offered for any definitive failure") {
                    result shouldBe
                        RenderedRow(
                            payload = rendered,
                            next = null,
                            fallback =
                                FallbackPlan(
                                    envelope = OutboundEnvelope(message = fallback, basicInfo = basicInfo),
                                    trigger = FallbackTrigger.ANY_DEFINITIVE_FAILURE,
                                ),
                        )
                    verify(exactly = 1) { slackRenderer.render(message = replace, basicInfo = basicInfo) }
                }
            }
        }

        given("slash replies that carry a direct-reply fallback for a refused channel") {
            val rendered = mockk<SlackEventPayload>()
            val slackRenderer = mockk<OutboundRenderer>()
            every { slackRenderer.render(message = any(), basicInfo = any()) } returns rendered
            val payloadRenderer = OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to slackRenderer))
            val requester = UserRef(id = "U_REQUESTER")
            val channel = ConversationTarget(id = basicInfo.channel)
            val list = MessageContent.Text(headline = "CodeCompanion — standup", markdown = "Active standup routines")
            val copy = directFallbackOf(channel = channel, recipient = requester, content = list)
            val notice = askRefusedNotice(channel = channel, recipient = requester, prompt = ASK_PROMPT)

            fun directReplyTo(reply: DirectReply): FallbackPlan =
                FallbackPlan(
                    envelope =
                        OutboundEnvelope(
                            message =
                                OutboundMessage.ChannelMessage(
                                    target = ConversationTarget(id = requester.id),
                                    content = reply.content,
                                ),
                            basicInfo = basicInfo.copy(channel = requester.id),
                        ),
                    trigger = FallbackTrigger.CHANNEL_REFUSED,
                )

            `when`("an ephemeral with a fallback copy is rendered") {
                val ephemeral =
                    OutboundMessage.Ephemeral(
                        target = channel,
                        recipient = requester,
                        content = list,
                        detailType = CommandDetailType.STANDUP_ROUTINE_LIST,
                        fallback = copy,
                    )
                val result = payloadRenderer.render(row = port.toRow(message = ephemeral, basicInfo = basicInfo))

                then("the reply is rendered as is, and its copy is offered to the recipient's DM on a refusal only") {
                    result shouldBe RenderedRow(payload = rendered, next = null, fallback = directReplyTo(reply = copy))
                    verify(exactly = 1) { slackRenderer.render(message = ephemeral, basicInfo = basicInfo) }
                    val dmInfo =
                        result.fallback
                            .shouldNotBeNull()
                            .envelope.basicInfo
                    dmInfo.channel shouldBe requester.id
                    dmInfo.publisherId shouldBe basicInfo.publisherId
                }
            }

            `when`("an /ask question with the ask-refused notice is rendered") {
                val question = createAgentQuestionMessage(prompt = ASK_PROMPT, fallback = notice)
                val result = payloadRenderer.render(row = port.toRow(message = question, basicInfo = basicInfo))

                then("the notice is a plain DM to the recipient: no ask detail type, so no turn can start from it") {
                    val plan = directReplyTo(reply = notice)
                    result.fallback shouldBe plan
                    plan.envelope.basicInfo.channel shouldBe requester.id
                    plan.envelope.basicInfo.publisherId shouldBe basicInfo.publisherId
                    plan.envelope.message
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .detailType shouldBe null
                }
            }

            `when`("the /ask question and then its staged notice are rendered through the Slack renderer") {
                val templateBuilder =
                    mockk<SlackTemplateBuilder> {
                        every {
                            simpleTextResponseTemplate(headLineText = any(), body = any(), isMarkDown = any())
                        } returns LayoutBlocks(template = emptyList())
                    }
                val realRenderer =
                    SlackOutboundRenderer(
                        slackEventBuilder =
                            SlackApiEventConstructor(botToken = TEST_BOT_TOKEN, templateBuilder = templateBuilder),
                    )
                val slackRows = OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to realRenderer))
                val question = createAgentQuestionMessage(prompt = ASK_PROMPT, fallback = notice)
                val questionRow = slackRows.render(row = port.toRow(message = question, basicInfo = basicInfo))
                val staged = questionRow.fallback.shouldNotBeNull().envelope
                val noticeRow =
                    slackRows.render(row = port.toRow(message = staged.message, basicInfo = staged.basicInfo))

                then("the question posts to the channel as an ask, the notice to the recipient as plain text") {
                    questionRow.payload.commandDetailType shouldBe CommandDetailType.AGENT_ASK_QUESTION
                    noticeRow.payload.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT
                    noticeRow.payload.channel shouldBe requester.id
                    noticeRow.payload
                        .shouldBeInstanceOf<PostEventPayloadContents>()
                        .body["channel"] shouldBe requester.id
                    noticeRow.fallback shouldBe null
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
