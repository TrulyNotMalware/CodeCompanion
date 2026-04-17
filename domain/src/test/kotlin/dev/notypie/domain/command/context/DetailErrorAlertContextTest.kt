package dev.notypie.domain.command.context

import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class DetailErrorAlertContextTest :
    BehaviorSpec({

        given("DetailErrorAlertContext with details") {
            val intentQueue = createIntentQueue()
            val idempotencyKey = UUID.randomUUID()
            val commandData = createAppMentionSlackCommandData()

            val context =
                DetailErrorAlertContext(
                    slackCommandData = commandData,
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

                then("should add ErrorDetail intent to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ErrorDetail>()
                    val errorIntent = intents.first() as CommandIntent.ErrorDetail
                    errorIntent.errorClassName shouldBe "TestClass"
                    errorIntent.errorMessage shouldBe "Something went wrong"
                    errorIntent.details shouldBe "Detailed error info"
                }
            }
        }

        given("DetailErrorAlertContext without details") {
            val intentQueue = createIntentQueue()
            val idempotencyKey = UUID.randomUUID()
            val commandData = createAppMentionSlackCommandData()

            val context =
                DetailErrorAlertContext(
                    slackCommandData = commandData,
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

                then("should add ErrorDetail intent with null details") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ErrorDetail>()
                    val errorIntent = intents.first() as CommandIntent.ErrorDetail
                    errorIntent.details shouldBe null
                }
            }
        }
    })
