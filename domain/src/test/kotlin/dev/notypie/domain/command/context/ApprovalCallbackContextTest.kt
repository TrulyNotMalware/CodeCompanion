package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ApprovalCallbackContextTest :
    BehaviorSpec({

        given("ApprovalCallbackContext with no participants") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    participants = emptySet(),
                    intents = intentQueue,
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

                then("no intents should be added to the queue") {
                    intentQueue.isEmpty() shouldBe true
                }
            }
        }

        given("ApprovalCallbackContext with participants") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()
            val participants = setOf("U001", "U002", "U003")

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    participants = participants,
                    intents = intentQueue,
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

                then("intents should be added for each participant") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 3
                    intents.forEach { intent ->
                        intent.shouldBeInstanceOf<CommandIntent.ApplyReject>()
                    }
                    val targetUsers = intents.map { (it as CommandIntent.ApplyReject).targetUserId }.toSet()
                    targetUsers shouldBe participants
                }
            }
        }

        given("ApprovalCallbackContext with custom ApprovalContents") {
            val intentQueue = createIntentQueue()
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
                    requestHeaders = SlackRequestHeaders(),
                    participants = setOf("U001"),
                    approvalContents = customApprovalContents,
                    intents = intentQueue,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should use custom approval contents and succeed") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("intent should contain custom approval contents") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val intent = intents.first() as CommandIntent.ApplyReject
                    intent.approvalContents.reason shouldBe "Custom approval reason"
                    intent.targetUserId shouldBe "U001"
                }
            }
        }

        given("ApprovalCallbackContext with default ApprovalContents") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    participants = setOf("U001"),
                    intents = intentQueue,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should create default approval contents and succeed") {
                    result.ok shouldBe true
                }

                then("should add ApplyReject intent to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ApplyReject>()
                }
            }
        }
    })
