package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SlackTextResponseContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}

        given("SlackTextResponseContext") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                SlackTextResponseContext(
                    text = "Hello from test",
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
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

                then("should add text response event to queue") {
                    eventQueue.poll() shouldNotBe null
                    eventQueue.flushQueue()
                }
            }
        }
    })
