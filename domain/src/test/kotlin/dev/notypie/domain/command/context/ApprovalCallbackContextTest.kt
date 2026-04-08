package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ApprovalCallbackContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}

        given("ApprovalCallbackContext with no participants") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    slackEventBuilder = eventBuilder,
                    requestHeaders = SlackRequestHeaders(),
                    events = eventQueue,
                    participants = emptySet(),
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("commandType should be PIPELINE") {
                    result.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be NOTICE_FORM") {
                    result.commandDetailType shouldBe CommandDetailType.NOTICE_FORM
                }

                then("ok should be true (vacuously true for empty list)") {
                    result.ok shouldBe true
                }

                then("status should be SUCCESS") {
                    result.status shouldBe Status.SUCCESS
                }

                then("no events should be added to the queue") {
                    eventQueue.poll() shouldBe null
                }
            }
        }

        given("ApprovalCallbackContext with participants") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()
            val participants = setOf("U001", "U002", "U003")

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    slackEventBuilder = eventBuilder,
                    requestHeaders = SlackRequestHeaders(),
                    events = eventQueue,
                    participants = participants,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("ok should be true") {
                    result.ok shouldBe true
                }

                then("status should be SUCCESS") {
                    result.status shouldBe Status.SUCCESS
                }

                then("actionStates should contain results from all participants") {
                    result.publisherId shouldBe basicInfo.publisherId
                    result.apiAppId shouldBe basicInfo.appId
                }

                then("events should be added for each participant") {
                    var eventCount = 0
                    while (eventQueue.poll() != null) eventCount++
                    eventCount shouldBe 3
                }
            }
        }

        given("ApprovalCallbackContext with custom ApprovalContents") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()

            val customApprovalContents =
                ApprovalContents(
                    reason = "Custom approval reason",
                    idempotencyKey = basicInfo.idempotencyKey,
                    commandDetailType = CommandDetailType.NOTICE_FORM,
                    publisherId = basicInfo.publisherId,
                    headLineText = "Custom Headline",
                )

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    slackEventBuilder = eventBuilder,
                    requestHeaders = SlackRequestHeaders(),
                    events = eventQueue,
                    participants = setOf("U001"),
                    approvalContents = customApprovalContents,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should use custom approval contents and succeed") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("event should be added") {
                    eventQueue.flushQueue()
                }
            }
        }

        given("ApprovalCallbackContext with default ApprovalContents") {
            val eventQueue = createDomainEventQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    slackEventBuilder = eventBuilder,
                    requestHeaders = SlackRequestHeaders(),
                    events = eventQueue,
                    participants = setOf("U001"),
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should create default approval contents and succeed") {
                    result.ok shouldBe true
                }

                then("should add event to queue") {
                    eventQueue.flushQueue()
                }
            }
        }
    })
