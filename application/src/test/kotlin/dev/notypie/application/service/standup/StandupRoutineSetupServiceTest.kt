package dev.notypie.application.service.standup

import dev.notypie.domain.command.createCreateStandupRoutineEvent
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.impl.command.event.SendSlackMessageEvent
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
                val stager = mockk<OutboundMessageStager>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        outboundStager = stager,
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
                every { stager.stage(message = any(), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

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

                then("the event mirrors production: the view_submission basicInfo carries no channel") {
                    event.payload.responseBasicInfo.channel shouldBe ""
                }

                then("a confirmation message is published to the command channel, not the blank submission channel") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                match { message ->
                                    message is OutboundMessage.Ephemeral &&
                                        message.target.id == "C_COMMAND" &&
                                        message.recipient == null &&
                                        message.detailType == CommandDetailType.STANDUP_SETUP_SUBMIT &&
                                        message.content.let {
                                            it is MessageContent.Text &&
                                                it.headline == null &&
                                                it.markdown.contains("Daily Standup") &&
                                                it.markdown.contains("created")
                                        }
                                },
                            basicInfo =
                                match {
                                    it.channel == "C_COMMAND" &&
                                        it.publisherId == event.payload.responseBasicInfo.publisherId &&
                                        it.idempotencyKey == event.payload.responseBasicInfo.idempotencyKey
                                },
                        )
                    }
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("the routine is valid but the repository write fails") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        outboundStager = stager,
                        eventPublisher = eventPublisher,
                    )
                every { repo.createRoutine(routine = any()) } throws IllegalStateException("db down")

                val failure =
                    runCatching {
                        service.createRoutine(
                            event = createCreateStandupRoutineEvent(),
                        )
                    }.exceptionOrNull()

                then("the failure propagates instead of a reply the caller's transaction could not commit") {
                    failure.shouldBeInstanceOf<IllegalStateException>().message shouldBe "db down"
                    verify(exactly = 0) { stager.stage(message = any(), basicInfo = any()) }
                }
            }

            `when`("the routine name carries a disguised link and an ampersand") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        outboundStager = stager,
                        eventPublisher = mockk(relaxed = true),
                    )
                val routineSlot = slot<Routine>()
                every { repo.createRoutine(routine = capture(routineSlot)) } answers { routineSlot.captured }
                val staged = slot<OutboundMessage>()
                every { stager.stage(message = capture(staged), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

                service.createRoutine(
                    event =
                        createCreateStandupRoutineEvent(
                            name = "<https://evil.example|Fill in standup> & co",
                            memberIds = listOf("U_ALICE"),
                        ),
                )

                then("the confirmation shows the name as literal text while the member mention stays markup") {
                    val markdown =
                        ((staged.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    markdown shouldContain "*&lt;https://evil.example|Fill in standup&gt; &amp; co*"
                    markdown shouldNotContain "<https://evil.example"
                    markdown shouldContain "<@U_ALICE>"
                }
            }

            `when`("the event carries no questions (invalid input)") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        outboundStager = stager,
                        eventPublisher = eventPublisher,
                    )
                val event = createCreateStandupRoutineEvent(questions = emptyList(), commandChannel = "C_COMMAND")
                val errorSlot = slot<OutboundMessage>()
                val errorInfoSlot = slot<CommandBasicInfo>()
                every { stager.stage(message = capture(errorSlot), basicInfo = capture(errorInfoSlot)) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

                service.createRoutine(event = event)

                then("the routine is never persisted") {
                    verify(exactly = 0) { repo.createRoutine(routine = any()) }
                }

                then("a friendly error ephemeral is published instead") {
                    val ephemeral = errorSlot.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body.contains("Couldn't create the standup routine") shouldBe true
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }

                then("the rejection also goes to the command channel, so the user learns nothing was created") {
                    (errorSlot.captured as OutboundMessage.Ephemeral).target.id shouldBe "C_COMMAND"
                    errorInfoSlot.captured.channel shouldBe "C_COMMAND"
                }
            }

            `when`("the cutoff did not parse as a whole number within bounds") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        outboundStager = stager,
                        eventPublisher = mockk(relaxed = true),
                    )
                val errorSlot = slot<OutboundMessage>()
                every { stager.stage(message = capture(errorSlot), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

                service.createRoutine(event = createCreateStandupRoutineEvent(cutoffMinutes = null))

                then("the routine is never persisted and the reply names the accepted cutoff range") {
                    verify(exactly = 0) { repo.createRoutine(routine = any()) }
                    val body =
                        ((errorSlot.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    body shouldContain "Couldn't create the standup routine"
                    body shouldContain "whole number of minutes between 1 and 1440"
                }
            }

            `when`("the cutoff is a number past the one-day bound") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                every { stager.stage(message = any(), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)
                val service =
                    StandupRoutineSetupService(
                        standupRepository = repo,
                        outboundStager = stager,
                        eventPublisher = mockk(relaxed = true),
                    )

                service.createRoutine(event = createCreateStandupRoutineEvent(cutoffMinutes = 1441L))

                then("Routine validation rejects it before anything is persisted") {
                    verify(exactly = 0) { repo.createRoutine(routine = any()) }
                }
            }
        }
    })
