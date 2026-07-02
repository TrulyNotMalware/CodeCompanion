package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext
import dev.notypie.domain.command.inbound.InboundSubmission
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
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    action = approveAction(isSelected = true),
                    submission =
                        InboundSubmission.StandupSetup(
                            idempotencyKeyRaw = UUID.randomUUID().toString(),
                            creatorId = "U_CREATOR",
                            commandChannel = "C_COMMAND",
                            name = "Daily Standup",
                            // Blank middle line must be trimmed away.
                            questionsRaw = "What did you do?\n\n  What are you doing?  \nBlockers?",
                            membersRaw = "U_ALICE,U_BOB",
                            summaryChannel = "C_SUMMARY",
                            weekdaysRaw = "MONDAY,WEDNESDAY,FRIDAY",
                            timeRaw = "09:30",
                            cutoffRaw = "90",
                            timezoneRaw = "UTC",
                        ),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
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
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    action = approveAction(isSelected = true),
                    submission =
                        InboundSubmission.StandupSetup(
                            idempotencyKeyRaw = UUID.randomUUID().toString(),
                            creatorId = "U_CREATOR",
                            commandChannel = "C_COMMAND",
                            name = "Routine",
                            questionsRaw = "Only question",
                            membersRaw = "",
                            summaryChannel = "",
                            weekdaysRaw = "",
                            timeRaw = "",
                            cutoffRaw = "",
                            timezoneRaw = "",
                        ),
                )

            `when`("handleInteraction is invoked without cutoff or timezone selections") {
                context.handleInteraction(interaction = payload)
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
