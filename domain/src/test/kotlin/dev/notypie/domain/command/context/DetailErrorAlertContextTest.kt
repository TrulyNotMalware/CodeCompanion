package dev.notypie.domain.command.context

import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class DetailErrorAlertContextTest :
    BehaviorSpec({

        given("DetailErrorAlertContext with details") {
            val intentQueue = createIntentQueue()
            val idempotencyKey = UUID.randomUUID()
            val commandData = createMentionInboundCommand()

            val context =
                DetailErrorAlertContext(
                    commandData = commandData,
                    targetClassName = "TestClass",
                    errorMessage = "Something went wrong",
                    details = "Detailed error info",
                    idempotencyKey = idempotencyKey,
                    intents = intentQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be SIMPLE") {
                    context.commandType shouldBe CommandType.SIMPLE
                }

                then("commandDetailType should be SIMPLE_TEXT") {
                    context.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT
                }
            }

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return success CommandOutput") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("commandType should be SIMPLE") {
                    result.commandType shouldBe CommandType.SIMPLE
                }

                then("should add a ChannelMessage ErrorNotice outbound to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val channelMessage = intents.first().shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    val content = channelMessage.content.shouldBeInstanceOf<MessageContent.ErrorNotice>()
                    content.className shouldBe "TestClass"
                    content.message shouldBe "Something went wrong"
                    content.details shouldBe "Detailed error info"
                }
            }
        }

        given("DetailErrorAlertContext without details") {
            val intentQueue = createIntentQueue()
            val idempotencyKey = UUID.randomUUID()
            val commandData = createMentionInboundCommand()

            val context =
                DetailErrorAlertContext(
                    commandData = commandData,
                    targetClassName = "TestClass",
                    errorMessage = "Error occurred",
                    details = null,
                    idempotencyKey = idempotencyKey,
                    intents = intentQueue,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return success CommandOutput even without details") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("should add a ChannelMessage ErrorNotice outbound with null details") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val channelMessage = intents.first().shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    channelMessage.content.shouldBeInstanceOf<MessageContent.ErrorNotice>().details shouldBe null
                }
            }
        }
    })
