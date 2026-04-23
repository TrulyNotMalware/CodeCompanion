package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.DeclineReasonSubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID

class DeclineReasonSubmissionContextTest :
    BehaviorSpec({

        fun submissionPayload(
            meetingKey: UUID,
            participantUserId: String,
            selectedReason: String,
            noticeChannel: String = "C_NOTICE",
            noticeMessageTs: String = "1700000000.000100",
        ) = createInteractionPayloadInput(
            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
            currentAction =
                States(
                    type = ActionElementTypes.APPLY_BUTTON,
                    isSelected = true,
                    selectedValue = selectedReason,
                ),
            states =
                listOf(
                    States(
                        type = ActionElementTypes.STATIC_SELECT,
                        isSelected = selectedReason.isNotBlank(),
                        selectedValue = selectedReason,
                    ),
                ),
            idempotencyKey = meetingKey,
        ).copy(
            routingExtras = listOf(participantUserId, noticeChannel, noticeMessageTs),
            privateMetadata =
                "$meetingKey,${CommandDetailType.DECLINE_REASON_MODAL.name}," +
                    "$participantUserId,$noticeChannel,$noticeMessageTs",
        )

        given("DeclineReasonSubmissionContext receives a valid view_submission") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT"
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                submissionPayload(
                    meetingKey = meetingKey,
                    participantUserId = participantUserId,
                    selectedReason = RejectReason.HEALTH_ISSUE.name,
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("result is a success with DECLINE_REASON_MODAL detail type") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    result.commandType shouldBe CommandType.PIPELINE
                    result.commandDetailType shouldBe CommandDetailType.DECLINE_REASON_MODAL
                }

                then("a single MeetingAttendanceUpdate intent carries the selected reason") {
                    val update =
                        intents.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    update.meetingIdempotencyKey shouldBe meetingKey
                    update.participantUserId shouldBe participantUserId
                    update.isAttending shouldBe false
                    update.absentReason shouldBe RejectReason.HEALTH_ISSUE
                }

                then("an UpdateNoticeMessage intent collapses the notice DM to a decline summary") {
                    val update =
                        intents.filterIsInstance<CommandIntent.UpdateNoticeMessage>().single()
                    update.channel shouldBe "C_NOTICE"
                    update.messageTs shouldBe "1700000000.000100"
                    update.markdownText shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.HEALTH_ISSUE.showMessage}"
                }

                then("MeetingAttendanceUpdate is emitted before UpdateNoticeMessage so persistence commits first") {
                    val attendanceIdx =
                        intents.indexOfFirst { it is CommandIntent.MeetingAttendanceUpdate }
                    val updateNoticeIdx =
                        intents.indexOfFirst { it is CommandIntent.UpdateNoticeMessage }
                    (attendanceIdx < updateNoticeIdx) shouldBe true
                }
            }
        }

        given("DeclineReasonSubmissionContext receives a submission with no notice routing context") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT"
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            // Legacy notice that predates Wave 2 — routing carries only participantUserId.
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                    currentAction =
                        States(
                            type = ActionElementTypes.APPLY_BUTTON,
                            isSelected = true,
                            selectedValue = RejectReason.HEALTH_ISSUE.name,
                        ),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.STATIC_SELECT,
                                isSelected = true,
                                selectedValue = RejectReason.HEALTH_ISSUE.name,
                            ),
                        ),
                    idempotencyKey = meetingKey,
                ).copy(routingExtras = listOf(participantUserId))

            `when`("handleInteraction is invoked") {
                context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("MeetingAttendanceUpdate still records the decline") {
                    intents
                        .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                        .single()
                        .absentReason shouldBe RejectReason.HEALTH_ISSUE
                }

                then("no UpdateNoticeMessage is emitted — we can't chat.update without channel + ts") {
                    intents.filterIsInstance<CommandIntent.UpdateNoticeMessage>() shouldBe emptyList()
                }
            }
        }

        given("DeclineReasonSubmissionContext receives an unknown reason value") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT"
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                submissionPayload(
                    meetingKey = meetingKey,
                    participantUserId = participantUserId,
                    selectedReason = "NOT_A_REAL_REASON",
                )

            `when`("handleInteraction is invoked") {
                context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("the attendance update falls back to RejectReason.OTHER") {
                    val update =
                        intents.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    update.absentReason shouldBe RejectReason.OTHER
                    update.isAttending shouldBe false
                }
            }
        }

        given("DeclineReasonSubmissionContext receives a submission that somehow selects ATTENDING") {
            val meetingKey = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            // ATTENDING must never appear in the decline modal — but a crafted payload could.
            val payload =
                submissionPayload(
                    meetingKey = meetingKey,
                    participantUserId = "U_PARTICIPANT",
                    selectedReason = RejectReason.ATTENDING.name,
                )

            `when`("handleInteraction is invoked") {
                context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("ATTENDING is coerced to OTHER so the decline still records as absent") {
                    val update =
                        intents.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    update.isAttending shouldBe false
                    update.absentReason shouldBe RejectReason.OTHER
                }
            }
        }

        given("DeclineReasonSubmissionContext receives a payload with malformed idempotencyKey") {
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val badPayload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = listOf(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(
                    idempotencyKey = "not-a-uuid",
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = badPayload)
                val intents = intentQueue.drainSnapshot()

                then("no intents are emitted and the context returns success so Slack closes the modal") {
                    result.ok shouldBe true
                    intents.shouldBeEmpty()
                }
            }
        }
    })
