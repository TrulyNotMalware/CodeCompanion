package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.TextResponseContext
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TextResponseContextTest :
    BehaviorSpec({

        given("TextResponseContext") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                TextResponseContext(
                    text = "Hello from test",
                    commandBasicInfo = basicInfo,
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

                then("should add a ChannelMessage Text outbound to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val channelMessage = intents.first().shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    channelMessage.target.id shouldBe basicInfo.channel
                    val content = channelMessage.content.shouldBeInstanceOf<MessageContent.Text>()
                    content.headline shouldBe "Simple Text Response"
                    content.markdown shouldBe "Hello from test"
                }
            }
        }
    })
