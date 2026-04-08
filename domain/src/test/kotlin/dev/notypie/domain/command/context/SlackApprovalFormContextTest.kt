package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SlackApprovalFormContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}

        given("SlackApprovalFormContext") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                SlackApprovalFormContext(
                    commandBasicInfo = basicInfo,
                    slackEventBuilder = eventBuilder,
                    requestHeaders = SlackRequestHeaders(),
                    events = eventQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be PIPELINE") {
                    context.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be APPROVAL_FORM") {
                    context.commandDetailType shouldBe CommandDetailType.APPROVAL_FORM
                }
            }

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return success CommandOutput") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("commandType should be PIPELINE") {
                    result.commandType shouldBe CommandType.PIPELINE
                }

                then("should add approval form event to queue") {
                    eventQueue.poll() shouldNotBe null
                    eventQueue.flushQueue()
                }
            }
        }
    })
