package dev.notypie.domain.command.context

import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.EphemeralTextResponseContext
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SendSlackMessageEvent
import dev.notypie.domain.dto.TestValidationData
import dev.notypie.domain.dto.shouldMatchExpected
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class EphemeralTextContextTest :
    BehaviorSpec({
        val testCommandBasicInfo = createCommandBasicInfo()
        val eventBuilder =
            mockEventBuilder {
                SlackEventBuilder::simpleEphemeralTextRequest returns
                    createSendSlackMessageEvent(
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    )
            }

        given("Ephemeral Text Context with no sub command") {
            val eventQueue = createDomainEventQueue()
            val noSubCommandContext =
                EphemeralTextResponseContext(
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    textMessage = "test message",
                    requestHeaders = SlackRequestHeaders(),
                )
            `when`("runCommand with no sub command") {
                val res = noSubCommandContext.runCommand()
                then("should return success result and create ephemeral text message event") {
                    val event = noSubCommandContext.events.poll()
                    val validationData =
                        TestValidationData(
                            commandDetailType = noSubCommandContext.commandDetailType,
                            commandType = noSubCommandContext.commandType,
                            commandBasicInfo = testCommandBasicInfo,
                        )
                    (res shouldMatchExpected validationData) shouldBe true
                    eventQueue.size shouldBe 0
                    event shouldNotBe null
                    event?.type shouldBe noSubCommandContext.commandDetailType
                    event?.name shouldBe SendSlackMessageEvent::class.java.simpleName
                    event?.payload?.javaClass shouldBe PostEventPayloadContents::class.java

                    eventQueue.flushQueue()
                }
            }
        }
    })
