package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.DeclineReasonSubmissionContext
import dev.notypie.domain.command.inbound.InboundField
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inboundField
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.entity.RejectReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class DeclineReasonSubmissionContextTest :
    BehaviorSpec({

        fun submissionPayload(
            meetingKey: UUID,
            participantUserId: String,
            selectedReason: String,
            noticeChannel: String = "C_NOTICE",
            noticeMessageTs: String = "1700000000.000100",
        ): InboundInteraction =
            createInboundInteraction(
                detailType = CommandDetailType.DECLINE_REASON_MODAL,
                action = approveAction(isSelected = true),
                form =
                    listOf(
                        inboundField(
                            kind = InboundFieldKind.CHOICE,
                            isSelected = selectedReason.isNotBlank(),
                            rawValue = selectedReason,
                        ),
                    ),
                idempotencyKey = meetingKey,
                routingExtras = listOf(participantUserId, noticeChannel, noticeMessageTs),
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
                val result = context.handleInteraction(interaction = payload)
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

                then("an UpdateMessage collapses the notice DM to a decline summary") {
                    val update =
                        intents.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe "C_NOTICE"
                    update.ref.messageId shouldBe "1700000000.000100"
                    update.detailType shouldBe CommandDetailType.DECLINE_REASON_MODAL
                    update.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.HEALTH_ISSUE.showMessage}"
                }

                then("MeetingAttendanceUpdate is emitted before UpdateMessage so persistence commits first") {
                    val attendanceIdx =
                        intents.indexOfFirst { it is CommandIntent.MeetingAttendanceUpdate }
                    val updateNoticeIdx =
                        intents.indexOfFirst { it is OutboundMessage.UpdateMessage }
                    (attendanceIdx < updateNoticeIdx) shouldBe true
                }
            }
        }

        given("DeclineReasonSubmissionContext receives an Other reason with a free-text detail") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT"
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.DECLINE_REASON_MODAL,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.CHOICE,
                                isSelected = true,
                                rawValue = RejectReason.OTHER.name,
                            ),
                            inboundField(
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "Out of town for a wedding",
                            ),
                        ),
                    idempotencyKey = meetingKey,
                    routingExtras = listOf(participantUserId, "C_NOTICE", "1700000000.000100"),
                )

            `when`("handleInteraction is invoked") {
                context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("MeetingAttendanceUpdate carries the Other reason and its detail") {
                    val update =
                        intents.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    update.absentReason shouldBe RejectReason.OTHER
                    update.absentReasonDetail shouldBe "Out of town for a wedding"
                }

                then("the notice summary appends the detail after the reason") {
                    val notice =
                        intents.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    notice.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.OTHER.showMessage} — " +
                        "Out of town for a wedding"
                }
            }
        }

        given("DeclineReasonSubmissionContext receives a non-Other reason with stray detail text") {
            val meetingKey = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                DeclineReasonSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.DECLINE_REASON_MODAL,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.CHOICE,
                                isSelected = true,
                                rawValue = RejectReason.VACATION.name,
                            ),
                            inboundField(
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "ignored because reason is not Other",
                            ),
                        ),
                    idempotencyKey = meetingKey,
                    routingExtras = listOf("U_PARTICIPANT"),
                )

            `when`("handleInteraction is invoked") {
                context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("detail is dropped — it is only meaningful for Other") {
                    val update =
                        intents.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    update.absentReason shouldBe RejectReason.VACATION
                    update.absentReasonDetail shouldBe null
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
                createInboundInteraction(
                    detailType = CommandDetailType.DECLINE_REASON_MODAL,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.CHOICE,
                                isSelected = true,
                                rawValue = RejectReason.HEALTH_ISSUE.name,
                            ),
                        ),
                    idempotencyKey = meetingKey,
                    routingExtras = listOf(participantUserId),
                )

            `when`("handleInteraction is invoked") {
                context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("MeetingAttendanceUpdate still records the decline") {
                    intents
                        .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                        .single()
                        .absentReason shouldBe RejectReason.HEALTH_ISSUE
                }

                then("no UpdateMessage is emitted — we can't chat.update without channel + ts") {
                    intents.filterIsInstance<OutboundMessage.UpdateMessage>() shouldBe emptyList()
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
                context.handleInteraction(interaction = payload)
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
                context.handleInteraction(interaction = payload)
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
                createInboundInteraction(
                    detailType = CommandDetailType.DECLINE_REASON_MODAL,
                    action = approveAction(isSelected = true),
                    form = emptyList<InboundField>(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(idempotencyKey = "not-a-uuid")

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = badPayload)
                val intents = intentQueue.drainSnapshot()

                then("no intents are emitted and the context returns success so Slack closes the modal") {
                    result.ok shouldBe true
                    intents.shouldBeEmpty()
                }
            }
        }
    })
