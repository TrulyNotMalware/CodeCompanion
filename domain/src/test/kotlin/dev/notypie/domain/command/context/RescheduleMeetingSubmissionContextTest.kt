package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.DATE_PICKER,
                                isSelected = true,
                                selectedValue =
                                    newDate.format(
                                        DateTimeFormatter.ofPattern(RescheduleMeetingSubmissionContext.DATE_PATTERN),
                                    ),
                                blockId = "reschedule_meeting_date",
                            ),
                            States(
                                type = ActionElementTypes.TIME_PICKER,
                                isSelected = true,
                                selectedValue =
                                    newTime.format(
                                        DateTimeFormatter.ofPattern(RescheduleMeetingSubmissionContext.TIME_PATTERN),
                                    ),
                                blockId = "reschedule_meeting_time",
                            ),
                        ),
                    idempotencyKey = meetingUid,
                ).copy(
                    routingExtras = listOf("U_HOST"),
                    privateMetadata =
                        "$meetingUid,${CommandDetailType.RESCHEDULE_MEETING_SUBMIT.name},U_HOST",
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.DATE_PICKER,
                                isSelected = true,
                                selectedValue = "2026-07-01",
                                blockId = "reschedule_meeting_date",
                            ),
                        ),
                    idempotencyKey = meetingUid,
                ).copy(
                    routingExtras = listOf("U_HOST"),
                    privateMetadata =
                        "$meetingUid,${CommandDetailType.RESCHEDULE_MEETING_SUBMIT.name},U_HOST",
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)

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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(idempotencyKey = "not-a-uuid")

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
