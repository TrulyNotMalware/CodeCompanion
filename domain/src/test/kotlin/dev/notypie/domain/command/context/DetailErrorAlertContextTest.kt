package dev.notypie.domain.command.context

import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class DetailErrorAlertContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}

        given("DetailErrorAlertContext with details") {
            val eventQueue = createDomainEventQueue()
            val idempotencyKey = UUID.randomUUID()
            val commandData = createAppMentionSlackCommandData()

            val context =
                DetailErrorAlertContext(
                    slackCommandData = commandData,
                    targetClassName = "TestClass",
                    errorMessage = "Something went wrong",
                    details = "Detailed error info",
                    events = eventQueue,
                    slackEventBuilder = eventBuilder,
                    idempotencyKey = idempotencyKey,
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

                then("should add error alert event to queue") {
                    eventQueue.poll() shouldNotBe null
                    eventQueue.flushQueue()
                }
            }
        }

        given("DetailErrorAlertContext without details") {
            val eventQueue = createDomainEventQueue()
            val idempotencyKey = UUID.randomUUID()
            val commandData = createAppMentionSlackCommandData()

            val context =
                DetailErrorAlertContext(
                    slackCommandData = commandData,
                    targetClassName = "TestClass",
                    errorMessage = "Error occurred",
                    details = null,
                    events = eventQueue,
                    slackEventBuilder = eventBuilder,
                    idempotencyKey = idempotencyKey,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return success CommandOutput even without details") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("should add event to queue") {
                    eventQueue.poll() shouldNotBe null
                    eventQueue.flushQueue()
                }
            }
        }
    })
