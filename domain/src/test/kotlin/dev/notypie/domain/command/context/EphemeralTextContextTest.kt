package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.EphemeralTextResponseContext
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class EphemeralTextContextTest :
    BehaviorSpec({
        val testCommandBasicInfo = createCommandBasicInfo()

        given("Ephemeral Text Context with no sub command") {
            val intentQueue = createIntentQueue()
            val noSubCommandContext =
                EphemeralTextResponseContext(
                    commandBasicInfo = testCommandBasicInfo,
                    textMessage = "test message",
                    intents = intentQueue,
                )
            `when`("runCommand with no sub command") {
                val res = noSubCommandContext.runCommand()
                then("should return success result and create ephemeral text intent") {
                    res.ok shouldBe true
                    res.status shouldBe Status.SUCCESS
                    res.commandType shouldBe CommandType.SIMPLE
                    res.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT

                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val ephemeral = intents.first().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.recipient shouldBe null
                    ephemeral.target.id shouldBe testCommandBasicInfo.channel
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe "test message"
                }
            }
        }

        given("Ephemeral Text Context with isOk=false") {
            val intentQueue = createIntentQueue()
            val errorContext =
                EphemeralTextResponseContext(
                    commandBasicInfo = testCommandBasicInfo,
                    textMessage = "error occurred",
                    isOk = false,
                    intents = intentQueue,
                )

            `when`("runCommand with isOk=false") {
                val res = errorContext.runCommand()
                then("should return fail result with ephemeral intent") {
                    res.ok shouldBe false
                    res.status shouldBe Status.FAILED

                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val ephemeral = intents.first().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe "error occurred"
                }
            }
        }
    })
