package dev.notypie.domain.command.context

import dev.notypie.domain.command.applyButtonField
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.MeetingApprovalResponseContext
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.rejectAction
import dev.notypie.domain.command.rejectButtonField
import dev.notypie.domain.meet.entity.RejectReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class MeetingApprovalResponseContextTest :
    BehaviorSpec({

        given("MeetingApprovalResponseContext receives a participant's APPROVE click") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()
            val context =
                MeetingApprovalResponseContext(
                    commandBasicInfo = basicInfo,
                    intents = intentQueue,
                )
            val meetingKey = UUID.randomUUID()
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    action = approveAction(isSelected = true),
                    form = listOf(applyButtonField()),
                    idempotencyKey = meetingKey,
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("result should be successful with MEETING_APPROVAL_NOTICE_FORM detail type") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    result.commandType shouldBe CommandType.PIPELINE
                    result.commandDetailType shouldBe CommandDetailType.MEETING_APPROVAL_NOTICE_FORM
                }

                then("a MeetingAttendanceUpdate intent with isAttending=true is emitted") {
                    val update =
                        intents
                            .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                            .single()
                    update.meetingIdempotencyKey shouldBe meetingKey
                    update.participantUserId shouldBe payload.actor.id
                    update.isAttending shouldBe true
                    update.absentReason shouldBe RejectReason.ATTENDING
                }

                then("a ReplaceMessage with accepted copy is emitted") {
                    val replace =
                        intents
                            .filterIsInstance<OutboundMessage.ReplaceMessage>()
                            .single()
                    replace.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        "You accepted the meeting invitation."
                    replace.handle.raw shouldBe payload.reply.raw
                }
            }
        }

        given("MeetingApprovalResponseContext receives a participant's DECLINE click") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()
            val context =
                MeetingApprovalResponseContext(
                    commandBasicInfo = basicInfo,
                    intents = intentQueue,
                )
            val meetingKey = UUID.randomUUID()
            // The notice DM is sent with ApprovalContents.subTitle propagated through the
            // routing text by SlackIntentResolver; the parser surfaces it as routingExtras[0].
            // Container.messageTs is what lets DeclineReasonSubmissionContext later chat.update
            // the original notice — carry it through so the modal's private_metadata can round-trip it.
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    action = rejectAction(isSelected = true),
                    form = listOf(rejectButtonField()),
                    idempotencyKey = meetingKey,
                    routingExtras = listOf("Weekly sync"),
                    message = MessageHandle(raw = "1700000000.000050"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("result should still be successful (decision recorded regardless)") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.MEETING_APPROVAL_NOTICE_FORM
                }

                then("an OpenModal effect is emitted with trigger/meeting/user/title context") {
                    val open =
                        intents
                            .filterIsInstance<OutboundMessage.OpenModal>()
                            .single()
                    open.handle.raw shouldBe payload.trigger.raw
                    val form = open.form.shouldBeInstanceOf<ModalForm.DeclineReason>()
                    form.meetingIdempotencyKey shouldBe meetingKey
                    form.participantUserId shouldBe payload.actor.id
                    // Title flows end-to-end from ApprovalContents.subTitle → routing text →
                    // parser.routingExtras[0] → form so the modal can render it.
                    form.meetingTitle shouldBe "Weekly sync"
                    // Channel + message_ts must flow through so the modal submission can later
                    // chat.update the original notice instead of leaving stale buttons.
                    form.originNotice?.conversation?.id shouldBe payload.channelId
                    form.originNotice?.messageId shouldBe "1700000000.000050"
                }

                then("a provisional MeetingAttendanceUpdate(OTHER) is emitted so the Deny is always recorded") {
                    val update =
                        intents
                            .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                            .single()
                    update.meetingIdempotencyKey shouldBe meetingKey
                    update.participantUserId shouldBe payload.actor.id
                    update.isAttending shouldBe false
                    update.absentReason shouldBe RejectReason.OTHER
                }

                then("OpenModal enqueued before provisional update (views.open wins the trigger race)") {
                    val openIndex = intents.indexOfFirst { it is OutboundMessage.OpenModal }
                    val updateIndex = intents.indexOfFirst { it is CommandIntent.MeetingAttendanceUpdate }
                    openIndex shouldBe 0
                    (openIndex < updateIndex) shouldBe true
                }

                then("no ReplaceMessage is emitted — the original notice must stay readable if the modal fails") {
                    intents.filterIsInstance<OutboundMessage.ReplaceMessage>() shouldBe emptyList()
                }
            }
        }

        given("routing regression: MEETING_APPROVAL_NOTICE_FORM must NOT run the creation form validation") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()
            val context =
                MeetingApprovalResponseContext(
                    commandBasicInfo = basicInfo,
                    intents = intentQueue,
                )
            // Payload carries only a button click (no MULTI_USERS_SELECT state).
            // Under the old routing this would trip "Select participants".
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("handleInteraction is invoked on a button-only payload") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("result should succeed without any validation error") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("no 'Select participants' ephemeral should ever be emitted") {
                    intents.none { intent ->
                        intent is OutboundMessage.Ephemeral &&
                            (intent.content as? MessageContent.Text)?.markdown?.contains("Select participants") == true
                    } shouldBe true
                }

                then("emitted intents are exactly MeetingAttendanceUpdate + ReplaceMessage") {
                    intents.any { it is CommandIntent.MeetingAttendanceUpdate }.shouldBeInstanceOf<Boolean>()
                    intents.count { it is CommandIntent.MeetingAttendanceUpdate } shouldBe 1
                    intents.count { it is OutboundMessage.ReplaceMessage } shouldBe 1
                }
            }
        }
    })
