package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.MeetingApprovalResponseContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.selectedApplyButtonStates
import dev.notypie.domain.command.selectedRejectButtonStates
import dev.notypie.domain.history.entity.Status
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    currentAction = selectedApplyButtonStates(),
                    states = listOf(selectedApplyButtonStates()),
                    idempotencyKey = meetingKey,
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
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
                    update.participantUserId shouldBe payload.user.id
                    update.isAttending shouldBe true
                    update.absentReason shouldBe RejectReason.ATTENDING
                }

                then("a ReplaceMessage intent with accepted copy is emitted") {
                    val replace =
                        intents
                            .filterIsInstance<CommandIntent.ReplaceMessage>()
                            .single()
                    replace.markdownText shouldBe "You accepted the meeting invitation."
                    replace.responseUrl shouldBe payload.responseUrl
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
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    currentAction = selectedRejectButtonStates(),
                    states = listOf(selectedRejectButtonStates()),
                    idempotencyKey = meetingKey,
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("result should still be successful (decision recorded regardless)") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.MEETING_APPROVAL_NOTICE_FORM
                }

                then("a MeetingAttendanceUpdate intent with isAttending=false is emitted") {
                    val update =
                        intents
                            .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                            .single()
                    update.meetingIdempotencyKey shouldBe meetingKey
                    update.participantUserId shouldBe payload.user.id
                    update.isAttending shouldBe false
                    update.absentReason shouldBe RejectReason.OTHER
                }

                then("a ReplaceMessage intent with declined copy is emitted") {
                    val replace =
                        intents
                            .filterIsInstance<CommandIntent.ReplaceMessage>()
                            .single()
                    replace.markdownText shouldBe "You declined the meeting invitation."
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    currentAction = selectedApplyButtonStates(),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("handleInteraction is invoked on a button-only payload") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("result should succeed without any validation error") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("no EphemeralResponse 'Select participants' intent should ever be emitted") {
                    intents.none { intent ->
                        intent is CommandIntent.EphemeralResponse &&
                            intent.message.contains("Select participants")
                    } shouldBe true
                }

                then("emitted intents are exactly MeetingAttendanceUpdate + ReplaceMessage") {
                    intents.any { it is CommandIntent.MeetingAttendanceUpdate }.shouldBeInstanceOf<Boolean>()
                    intents.count { it is CommandIntent.MeetingAttendanceUpdate } shouldBe 1
                    intents.count { it is CommandIntent.ReplaceMessage } shouldBe 1
                }
            }
        }
    })
