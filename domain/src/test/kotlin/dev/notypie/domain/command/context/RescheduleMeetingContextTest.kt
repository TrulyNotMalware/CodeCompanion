package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingContext
import dev.notypie.domain.command.inbound.ReplyHandle
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST,
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
                    result.commandDetailType shouldBe CommandDetailType.MEETING_RESCHEDULE_REQUEST
                }

                then("OpenModal carries the trigger id, meeting uid, requester, channel and the list's reply handle") {
                    val open = intents.filterIsInstance<OutboundMessage.OpenModal>().single()
                    open.handle.raw shouldBe triggerId
                    val form = open.form.shouldBeInstanceOf<ModalForm.Reschedule>()
                    form.meetingUid shouldBe meetingUid
                    form.requesterId shouldBe payload.actor.id
                    form.channel.id shouldBe payload.channelId
                    form.listHandle shouldBe ResponseReplaceHandle(raw = payload.reply.raw)
                }
            }

            `when`("the click carries no reply handle") {
                val noReplyIntents = createIntentQueue()
                RescheduleMeetingContext(commandBasicInfo = createCommandBasicInfo(), intents = noReplyIntents)
                    .handleInteraction(interaction = payload.copy(reply = ReplyHandle(raw = "")))

                then("the modal is still opened, with no list to close") {
                    val open = noReplyIntents.drainSnapshot().filterIsInstance<OutboundMessage.OpenModal>().single()
                    open.form.shouldBeInstanceOf<ModalForm.Reschedule>().listHandle shouldBe null
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
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST,
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
