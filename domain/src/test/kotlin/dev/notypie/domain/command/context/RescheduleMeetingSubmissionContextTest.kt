package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inboundField
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class RescheduleMeetingSubmissionContextTest :
    BehaviorSpec({
        given("RescheduleMeetingSubmissionContext receives a valid view_submission") {
            val meetingUid = UUID.randomUUID()
            val newDate = LocalDate.of(2026, 7, 1)
            val newTime = LocalTime.of(14, 30)
            val intentQueue = createIntentQueue()
            val context =
                RescheduleMeetingSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.DATE,
                                isSelected = true,
                                rawValue =
                                    newDate.format(
                                        DateTimeFormatter.ofPattern(RescheduleMeetingSubmissionContext.DATE_PATTERN),
                                    ),
                                key = "reschedule_meeting_date",
                            ),
                            inboundField(
                                kind = InboundFieldKind.TIME,
                                isSelected = true,
                                rawValue =
                                    newTime.format(
                                        DateTimeFormatter.ofPattern(RescheduleMeetingSubmissionContext.TIME_PATTERN),
                                    ),
                                key = "reschedule_meeting_time",
                            ),
                        ),
                    idempotencyKey = meetingUid,
                    routingExtras = listOf("U_HOST"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.RESCHEDULE_MEETING_SUBMIT
                }

                then("RescheduleMeeting carries the parsed meeting uid, requester, and new start") {
                    val reschedule = intents.filterIsInstance<CommandIntent.RescheduleMeeting>().single()
                    reschedule.meetingUid shouldBe meetingUid
                    reschedule.requesterId shouldBe "U_HOST"
                    reschedule.newStartAt shouldBe LocalDateTime.of(newDate, newTime)
                }
            }
        }

        given("RescheduleMeetingSubmissionContext receives a submission missing the time picker") {
            val meetingUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                RescheduleMeetingSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.DATE,
                                isSelected = true,
                                rawValue = "2026-07-01",
                                key = "reschedule_meeting_date",
                            ),
                        ),
                    idempotencyKey = meetingUid,
                    routingExtras = listOf("U_HOST"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }

        given("RescheduleMeetingSubmissionContext receives malformed meeting metadata") {
            val intentQueue = createIntentQueue()
            val context =
                RescheduleMeetingSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(idempotencyKey = "not-a-uuid")

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
