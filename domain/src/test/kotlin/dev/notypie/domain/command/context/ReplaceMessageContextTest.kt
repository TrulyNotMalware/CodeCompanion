package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReplaceMessageContext
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ReplaceMessageContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}

        given("ReplaceMessageContext") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ReplaceMessageContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    responseUrl = TEST_BASE_URL,
                    markdownMessage = "Replaced successfully.",
                )

            `when`("checking command metadata") {
                then("commandType should be SIMPLE") {
                    context.commandType shouldBe CommandType.SIMPLE
                }

                then("commandDetailType should be REPLACE_TEXT") {
                    context.commandDetailType shouldBe CommandDetailType.REPLACE_TEXT
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

                then("should add replace text event to queue") {
                    eventQueue.poll() shouldNotBe null
                    eventQueue.flushQueue()
                }
            }

            `when`("handleInteraction") {
                val interactionEventQueue = createDomainEventQueue()
                val interactionContext =
                    ReplaceMessageContext(
                        commandBasicInfo = basicInfo,
                        requestHeaders = SlackRequestHeaders(),
                        slackEventBuilder = eventBuilder,
                        events = interactionEventQueue,
                        responseUrl = TEST_BASE_URL,
                        markdownMessage = "Interaction replaced.",
                    )

                val result =
                    interactionContext.handleInteraction(
                        interactionPayload = createInteractionPayloadInput(),
                    )

                then("should return success CommandOutput") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("should add event to queue") {
                    interactionEventQueue.poll() shouldNotBe null
                    interactionEventQueue.flushQueue()
                }
            }
        }
    })
