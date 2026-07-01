package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupFillContext
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.selectedApplyButtonStates
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_FILL,
                    currentAction = selectedApplyButtonStates(),
                    states = listOf(selectedApplyButtonStates()),
                    idempotencyKey = UUID.randomUUID(),
                    triggerId = "trigger-standup",
                ).let { base ->
                    base.copy(
                        routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
                        container = base.container.copy(messageTs = "1700000000.000200"),
                    )
                }

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_FILL
                }

                then("an OpenModal effect carries routing and notice update context") {
                    val open = intents.filterIsInstance<OutboundMessage.OpenModal>().single()
                    open.handle.raw shouldBe "trigger-standup"
                    val form = open.form.shouldBeInstanceOf<ModalForm.StandupFill>()
                    form.sessionUid shouldBe sessionUid
                    form.routineUid shouldBe routineUid
                    form.requesterId shouldBe payload.user.id
                    form.originNotice.conversation.id shouldBe payload.channel.id
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_FILL,
                    currentAction = selectedApplyButtonStates(),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(routingExtras = listOf("not-a-uuid"))

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)

                then("Slack still receives a success response and no intent is emitted") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
