package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class StandupSetupSubmissionContextTest :
    BehaviorSpec({
        given("StandupSetupSubmissionContext receives a valid view_submission") {
            val intentQueue = createIntentQueue()
            val context =
                StandupSetupSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "Daily Standup",
                                blockId = StandupSetupSubmissionContext.NAME_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                // Blank middle line must be trimmed away.
                                selectedValue = "What did you do?\n\n  What are you doing?  \nBlockers?",
                                blockId = StandupSetupSubmissionContext.QUESTIONS_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.MULTI_USERS_SELECT,
                                isSelected = true,
                                selectedValue = "U_ALICE,U_BOB",
                                blockId = StandupSetupSubmissionContext.MEMBERS_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.CONVERSATIONS_SELECT,
                                isSelected = true,
                                selectedValue = "C_SUMMARY",
                                blockId = StandupSetupSubmissionContext.SUMMARY_CHANNEL_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.MULTI_STATIC_SELECT,
                                isSelected = true,
                                selectedValue = "MONDAY,WEDNESDAY,FRIDAY",
                                blockId = StandupSetupSubmissionContext.WEEKDAYS_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.TIME_PICKER,
                                isSelected = true,
                                selectedValue = "09:30",
                                blockId = StandupSetupSubmissionContext.TIME_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "90",
                                blockId = StandupSetupSubmissionContext.CUTOFF_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.STATIC_SELECT,
                                isSelected = true,
                                selectedValue = "UTC",
                                blockId = StandupSetupSubmissionContext.TIMEZONE_BLOCK_ID,
                            ),
                        ),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(
                    routingExtras = listOf("U_CREATOR", "C_COMMAND"),
                    privateMetadata =
                        "${UUID.randomUUID()},${CommandDetailType.STANDUP_SETUP_SUBMIT.name},U_CREATOR,C_COMMAND",
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_SETUP_SUBMIT
                }

                then("CreateStandupRoutine carries every parsed field") {
                    val create = intents.filterIsInstance<CommandIntent.CreateStandupRoutine>().single()
                    create.name shouldBe "Daily Standup"
                    create.creatorId shouldBe "U_CREATOR"
                    create.commandChannel shouldBe "C_COMMAND"
                    create.summaryChannel shouldBe "C_SUMMARY"
                    create.questions shouldContainExactly
                        listOf("What did you do?", "What are you doing?", "Blockers?")
                    create.memberIds shouldContainExactly listOf("U_ALICE", "U_BOB")
                    create.weekdays shouldContainExactlyInAnyOrder
                        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                    create.triggerLocalTime shouldBe LocalTime.of(9, 30)
                    create.cutoffMinutes shouldBe 90L
                    create.timezone shouldBe ZoneId.of("UTC")
                }
            }
        }

        given("StandupSetupSubmissionContext receives a submission with missing optional values") {
            val intentQueue = createIntentQueue()
            val context =
                StandupSetupSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "Routine",
                                blockId = StandupSetupSubmissionContext.NAME_BLOCK_ID,
                            ),
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "Only question",
                                blockId = StandupSetupSubmissionContext.QUESTIONS_BLOCK_ID,
                            ),
                        ),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("handleInteraction is invoked without cutoff or timezone selections") {
                context.handleInteraction(interactionPayload = payload)
                val create =
                    intentQueue
                        .drainSnapshot()
                        .filterIsInstance<CommandIntent.CreateStandupRoutine>()
                        .single()

                then("cutoff and timezone fall back to their defaults") {
                    create.cutoffMinutes shouldBe StandupSetupSubmissionContext.DEFAULT_CUTOFF_MINUTES
                    create.timezone shouldBe ZoneId.of("Asia/Seoul")
                    create.triggerLocalTime shouldBe LocalTime.of(10, 0)
                }
            }
        }
    })
