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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
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

        given("RescheduleMeetingContext receives a click whose routing extras may carry the meeting's start") {
            val meetingUid = UUID.randomUUID()

            fun openedForm(routingExtras: List<String>): ModalForm.Reschedule {
                val intentQueue = createIntentQueue()
                RescheduleMeetingContext(commandBasicInfo = createCommandBasicInfo(), intents = intentQueue)
                    .handleInteraction(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST,
                                action = approveAction(isSelected = true),
                                form = emptyList(),
                                idempotencyKey = UUID.randomUUID(),
                                trigger = TriggerHandle(raw = "trigger.123"),
                                routingExtras = routingExtras,
                            ),
                    )
                return intentQueue
                    .drainSnapshot()
                    .filterIsInstance<OutboundMessage.OpenModal>()
                    .single()
                    .form
                    .shouldBeInstanceOf<ModalForm.Reschedule>()
            }

            `when`("the second routing extra is an ISO local date-time") {
                val form = openedForm(routingExtras = listOf(meetingUid.toString(), "2026-11-03T10:00"))

                then("the modal form carries it as the current start") {
                    form.meetingUid shouldBe meetingUid
                    form.currentStartAt shouldBe LocalDateTime.of(2026, 11, 3, 10, 0)
                }
            }

            `when`("the click comes from a list rendered before the start was carried (meeting uid only)") {
                val form = openedForm(routingExtras = listOf(meetingUid.toString()))

                then("the modal still opens and the form carries no start instead of an invented one") {
                    form.meetingUid shouldBe meetingUid
                    form.currentStartAt.shouldBeNull()
                }
            }

            `when`("the second routing extra is not a date-time") {
                val form = openedForm(routingExtras = listOf(meetingUid.toString(), "garbage"))

                then("the modal still opens with no start and nothing is thrown") {
                    form.meetingUid shouldBe meetingUid
                    form.currentStartAt.shouldBeNull()
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
