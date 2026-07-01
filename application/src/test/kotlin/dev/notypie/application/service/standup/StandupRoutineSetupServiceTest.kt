package dev.notypie.application.service.standup

import dev.notypie.domain.command.createCreateStandupRoutineEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.event.SendSlackMessageEvent
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

class StandupRoutineSetupServiceTest :
    BehaviorSpec({
        given("createRoutine") {
            `when`("a valid CreateStandupRoutineEvent is received") {
                val repo = mockk<StandupRepository>()
                val slackEventBuilder = mockk<SlackApiEventConstructor>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        slackEventBuilder = slackEventBuilder,
                        eventPublisher = eventPublisher,
                    )
                val event =
                    createCreateStandupRoutineEvent(
                        name = "Daily Standup",
                        creatorId = "U_CREATOR",
                        commandChannel = "C_COMMAND",
                        summaryChannel = "C_SUMMARY",
                        questions = listOf("What did you do?", "What are you doing?"),
                        memberIds = listOf("U_ALICE", "U_BOB"),
                        weekdays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY),
                        triggerLocalTime = LocalTime.of(9, 30),
                        cutoffMinutes = 90L,
                        timezone = ZoneId.of("UTC"),
                    )
                val routineSlot = slot<Routine>()
                every { repo.createRoutine(routine = capture(routineSlot)) } answers { routineSlot.captured }
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns mockk<SendSlackMessageEvent>(relaxed = true)

                service.createRoutine(event = event)

                then("the repository persists a Routine built from the event with members attached") {
                    verify(exactly = 1) { repo.createRoutine(routine = any()) }
                    val persisted = routineSlot.captured
                    persisted.name shouldBe "Daily Standup"
                    persisted.creatorId shouldBe "U_CREATOR"
                    persisted.commandChannel shouldBe "C_COMMAND"
                    persisted.summaryChannel shouldBe "C_SUMMARY"
                    persisted.cutoffOffset shouldBe Duration.ofMinutes(90L)
                    persisted.routineTimezone shouldBe ZoneId.of("UTC")
                    persisted.memberIdSnapshot() shouldContainExactlyInAnyOrder setOf("U_ALICE", "U_BOB")
                }

                then("every member adopts the routine timezone") {
                    routineSlot.captured.memberSnapshot().forEach { member ->
                        member.userTimezone shouldBe ZoneId.of("UTC")
                    }
                }

                then("a confirmation message is published to the command channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = match { it.contains("Daily Standup") && it.contains("created") },
                            commandBasicInfo = match { it.channel == "C_COMMAND" },
                            commandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                            targetUserId = null,
                        )
                    }
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("the event carries no questions (invalid input)") {
                val repo = mockk<StandupRepository>()
                val slackEventBuilder = mockk<SlackApiEventConstructor>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        slackEventBuilder = slackEventBuilder,
                        eventPublisher = eventPublisher,
                    )
                val event = createCreateStandupRoutineEvent(questions = emptyList())
                val errorSlot = slot<String>()
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(errorSlot),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns mockk<SendSlackMessageEvent>(relaxed = true)

                service.createRoutine(event = event)

                then("the routine is never persisted") {
                    verify(exactly = 0) { repo.createRoutine(routine = any()) }
                }

                then("a friendly error ephemeral is published instead") {
                    errorSlot.captured.contains("Couldn't create the standup routine") shouldBe true
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }
        }
    })
