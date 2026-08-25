package dev.notypie.domain.command.context

import dev.notypie.domain.command.applyButtonField
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupFillContext
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class StandupFillContextTest :
    BehaviorSpec({
        given("StandupFillContext receives a Fill button click") {
            val sessionUid = UUID.randomUUID()
            val routineUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                StandupFillContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_PROMPT,
                    action = approveAction(isSelected = true),
                    form = listOf(applyButtonField()),
                    idempotencyKey = UUID.randomUUID(),
                    trigger = TriggerHandle(raw = "trigger-standup"),
                    message = MessageHandle(raw = "1700000000.000200"),
                    routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_PROMPT
                }

                then("an OpenModal effect carries routing and notice update context") {
                    val open = intents.filterIsInstance<OutboundMessage.OpenModal>().single()
                    open.handle.raw shouldBe "trigger-standup"
                    val form = open.form.shouldBeInstanceOf<ModalForm.StandupFill>()
                    form.sessionUid shouldBe sessionUid
                    form.routineUid shouldBe routineUid
                    form.requesterId shouldBe payload.actor.id
                    form.originNotice.conversation.id shouldBe payload.channelId
                    form.originNotice.messageId shouldBe "1700000000.000200"
                }
            }
        }

        given("StandupFillContext receives malformed routing extras") {
            val intentQueue = createIntentQueue()
            val context =
                StandupFillContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_PROMPT,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    routingExtras = listOf("not-a-uuid"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("Slack still receives a success response and no intent is emitted") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
