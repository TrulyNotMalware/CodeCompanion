package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.AddParticipantContext
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class AddParticipantContextTest :
    BehaviorSpec({
        given("AddParticipantContext receives an Add participant button click for a host-owned meeting") {
            val meetingUid = UUID.randomUUID()
            val triggerId = "trigger.123"
            val intentQueue = createIntentQueue()
            val context =
                AddParticipantContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    trigger = TriggerHandle(raw = triggerId),
                    routingExtras = listOf(meetingUid.toString()),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST
                }

                then("OpenModal carries the trigger id, meeting uid, requester, and channel") {
                    val open = intents.filterIsInstance<OutboundMessage.OpenModal>().single()
                    open.handle.raw shouldBe triggerId
                    val form = open.form.shouldBeInstanceOf<ModalForm.AddParticipant>()
                    form.meetingUid shouldBe meetingUid
                    form.requesterId shouldBe payload.actor.id
                    form.channel.id shouldBe payload.channelId
                }
            }
        }

        given("AddParticipantContext receives a click with a malformed meeting uid") {
            val intentQueue = createIntentQueue()
            val context =
                AddParticipantContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    trigger = TriggerHandle(raw = "trigger.123"),
                    routingExtras = listOf("not-a-uuid"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
