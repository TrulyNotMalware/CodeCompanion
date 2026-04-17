package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.RequestApprovalContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
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
                    requestHeaders = SlackRequestHeaders(),
                    basicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be PIPELINE") {
                    context.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be REQUEST_APPLY_FORM") {
                    context.commandDetailType shouldBe CommandDetailType.REQUEST_APPLY_FORM
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

                then("should add ApplyReject intent to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ApplyReject>()
                    val applyRejectIntent = intents.first() as CommandIntent.ApplyReject
                    applyRejectIntent.approvalContents.reason shouldBe "approve this PR"
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
                    requestHeaders = SlackRequestHeaders(),
                    basicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand with a reason in commands") {
                val result = context.runCommand()

                then("should use the reason from commands queue") {
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
