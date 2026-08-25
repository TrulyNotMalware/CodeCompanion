package dev.notypie.domain.command.context

import dev.notypie.domain.command.createApprovalContents
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.outbound.OutboundMessage
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
                    participants = emptySet(),
                    intents = intentQueue,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("commandType should be PIPELINE") {
                    result.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be APPROVAL_CALLBACK") {
                    result.commandDetailType shouldBe CommandDetailType.APPROVAL_CALLBACK
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

                then("result should carry basic info from all participants") {
                    result.publisherId shouldBe basicInfo.publisherId
                    result.apiAppId shouldBe basicInfo.appId
                }

                then("an Approval outbound message should be added for each participant") {
                    val effects = intentQueue.snapshot()
                    effects.size shouldBe 3
                    effects.forEach { effect ->
                        effect.shouldBeInstanceOf<OutboundMessage.Approval>()
                    }
                    val targetUsers = effects.map { (it as OutboundMessage.Approval).recipient?.id }.toSet()
                    targetUsers shouldBe participants
                }
            }
        }

        given("ApprovalCallbackContext with custom ApprovalContents") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val customApprovalContents =
                createApprovalContents(
                    reason = "Custom approval reason",
                    idempotencyKey = basicInfo.idempotencyKey,
                    commandDetailType = CommandDetailType.APPROVAL_CALLBACK,
                    publisherId = basicInfo.publisherId,
                    headLineText = "Custom Headline",
                )

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
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

                then("outbound message should contain custom approval contents") {
                    val effects = intentQueue.snapshot()
                    effects.size shouldBe 1
                    val approval = effects.first() as OutboundMessage.Approval
                    approval.approval.reason shouldBe "Custom approval reason"
                    approval.recipient?.id shouldBe "U001"
                }
            }
        }

        given("ApprovalCallbackContext with default ApprovalContents") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ApprovalCallbackContext(
                    commandBasicInfo = basicInfo,
                    participants = setOf("U001"),
                    intents = intentQueue,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should create default approval contents and succeed") {
                    result.ok shouldBe true
                }

                then("should add an Approval outbound message to the queue") {
                    val effects = intentQueue.snapshot()
                    effects.size shouldBe 1
                    effects.first().shouldBeInstanceOf<OutboundMessage.Approval>()
                }
            }
        }
    })
