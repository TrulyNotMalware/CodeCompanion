package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingContext
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID

class RescheduleMeetingContextTest :
    BehaviorSpec({
        given("RescheduleMeetingContext receives a Reschedule button click for a host-owned meeting") {
            val meetingUid = UUID.randomUUID()
            val triggerId = "trigger.123"
            val intentQueue = createIntentQueue()
            val context =
                RescheduleMeetingContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.RESCHEDULE_MEETING,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    triggerId = triggerId,
                ).copy(routingExtras = listOf(meetingUid.toString()))

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.RESCHEDULE_MEETING
                }

                then("OpenRescheduleMeetingModal carries the trigger id, meeting uid, and requester") {
                    val open = intents.filterIsInstance<CommandIntent.OpenRescheduleMeetingModal>().single()
                    open.triggerId shouldBe triggerId
                    open.meetingUid shouldBe meetingUid
                    open.requesterId shouldBe payload.user.id
                }
            }
        }

        given("RescheduleMeetingContext receives a click with a malformed meeting uid") {
            val intentQueue = createIntentQueue()
            val context =
                RescheduleMeetingContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.RESCHEDULE_MEETING,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    triggerId = "trigger.123",
                ).copy(routingExtras = listOf("not-a-uuid"))

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
