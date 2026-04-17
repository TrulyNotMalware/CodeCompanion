package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReplaceMessageContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ReplaceMessageContextTest :
    BehaviorSpec({

        given("ReplaceMessageContext") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ReplaceMessageContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    responseUrl = TEST_BASE_URL,
                    markdownMessage = "Replaced successfully.",
                    intents = intentQueue,
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

                then("should add ReplaceMessage intent to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                    val replaceIntent = intents.first() as CommandIntent.ReplaceMessage
                    replaceIntent.markdownText shouldBe "Replaced successfully."
                    replaceIntent.responseUrl shouldBe TEST_BASE_URL
                }
            }

            `when`("handleInteraction") {
                val interactionIntentQueue = createIntentQueue()
                val interactionContext =
                    ReplaceMessageContext(
                        commandBasicInfo = basicInfo,
                        requestHeaders = SlackRequestHeaders(),
                        responseUrl = TEST_BASE_URL,
                        markdownMessage = "Interaction replaced.",
                        intents = interactionIntentQueue,
                    )

                val result =
                    interactionContext.handleInteraction(
                        interactionPayload = createInteractionPayloadInput(),
                    )

                then("should return success CommandOutput") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("should add ReplaceMessage intent to the queue") {
                    val intents = interactionIntentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                    val replaceIntent = intents.first() as CommandIntent.ReplaceMessage
                    replaceIntent.markdownText shouldBe "Interaction replaced."
                }
            }
        }
    })
