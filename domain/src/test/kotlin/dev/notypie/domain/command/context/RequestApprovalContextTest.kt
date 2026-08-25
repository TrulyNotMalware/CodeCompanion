package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.RequestApprovalContext
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.LinkedList

class RequestApprovalContextTest :
    BehaviorSpec({

        given("RequestApprovalContext") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()
            val users = LinkedList(listOf("U001", "U002"))
            val commands = LinkedList(listOf("approve this PR"))

            val context =
                RequestApprovalContext(
                    users = users,
                    commands = commands,
                    basicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be PIPELINE") {
                    context.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be APPLY_REQUEST") {
                    context.commandDetailType shouldBe CommandDetailType.APPLY_REQUEST
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

                then("should add an Approval outbound message to the queue") {
                    val effects = intentQueue.snapshot()
                    effects.size shouldBe 1
                    effects.first().shouldBeInstanceOf<OutboundMessage.Approval>()
                    val approval = effects.first() as OutboundMessage.Approval
                    approval.approval.reason shouldBe "approve this PR"
                    approval.recipient shouldBe null
                    approval.target.id shouldBe basicInfo.channel
                }
            }
        }

        given("RequestApprovalContext with empty commands queue") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()
            val users = LinkedList<String>()
            val commands = LinkedList(listOf("reason text"))

            val context =
                RequestApprovalContext(
                    users = users,
                    commands = commands,
                    basicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand with a reason in commands") {
                val result = context.runCommand()

                then("should use the reason from commands queue") {
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
