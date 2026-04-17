package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SlackApprovalFormContextTest :
    BehaviorSpec({

        given("SlackApprovalFormContext") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                SlackApprovalFormContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    intents = intentQueue,
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

                then("should add ApprovalForm intent to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ApprovalForm>()
                    val formIntent = intents.first() as CommandIntent.ApprovalForm
                    formIntent.headLine shouldBe "Approve Form"
                    formIntent.selectionFields.size shouldBe 1
                }
            }
        }
    })
