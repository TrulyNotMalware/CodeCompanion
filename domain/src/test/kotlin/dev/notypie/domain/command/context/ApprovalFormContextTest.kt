package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ApprovalFormContextTest :
    BehaviorSpec({

        given("ApprovalFormContext") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                ApprovalFormContext(
                    commandBasicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be PIPELINE") {
                    context.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be APPROVAL_FORM") {
                    context.commandDetailType shouldBe CommandDetailType.APPROVAL_REQUEST
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

                then("should add a Form ChannelMessage to the queue") {
                    val effects = intentQueue.snapshot()
                    effects.size shouldBe 1
                    val channelMessage = effects.first() as OutboundMessage.ChannelMessage
                    val form = channelMessage.content.shouldBeInstanceOf<MessageContent.Form>()
                    form.headline shouldBe "Approve Form"
                    form.fields.size shouldBe 1
                }
            }
        }
    })
