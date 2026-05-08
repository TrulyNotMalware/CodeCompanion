package dev.notypie.application.service.standup

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerPayload
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StandupAnswerServiceTest :
    BehaviorSpec({
        given("recordAnswer") {
            `when`("a RecordStandupAnswerEvent is received") {
                val repo = mockk<StandupRepository>()
                val now = Instant.parse("2026-05-01T02:00:00Z")
                val service =
                    StandupAnswerService(
                        standupRepository = repo,
                        slackEventBuilder = mockk(),
                        eventPublisher = mockk(),
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                    )
                val sessionUid = UUID.randomUUID()
                val event =
                    RecordStandupAnswerEvent(
                        idempotencyKey = UUID.randomUUID(),
                        payload =
                            RecordStandupAnswerPayload(
                                sessionUid = sessionUid,
                                userId = "U_STANDUP",
                                responses = listOf("Done", "Next"),
                            ),
                        type = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    )
                every {
                    repo.recordAnswer(
                        sessionUid = sessionUid,
                        userId = "U_STANDUP",
                        responses = listOf("Done", "Next"),
                        submittedAt = now,
                    )
                } returns true

                service.recordAnswer(event = event)

                then("the repository receives the submitted responses with the service clock timestamp") {
                    verify(exactly = 1) {
                        repo.recordAnswer(
                            sessionUid = sessionUid,
                            userId = "U_STANDUP",
                            responses = listOf("Done", "Next"),
                            submittedAt = now,
                        )
                    }
                }
            }
        }

        given("onStandupModalOpenFailed") {
            `when`("views.open failed for the standup modal") {
                val slackEventBuilder = mockk<SlackApiEventConstructor>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupAnswerService(
                        standupRepository = mockk(),
                        slackEventBuilder = slackEventBuilder,
                        eventPublisher = eventPublisher,
                        clock = Clock.fixed(Instant.now(), ZoneOffset.UTC),
                    )
                val idempotencyKey = UUID.randomUUID()
                val event =
                    StandupModalOpenFailedEvent(
                        userId = "U_STANDUP",
                        apiAppId = "A_STANDUP",
                        channel = "D_STANDUP",
                        idempotencyKey = idempotencyKey,
                        reason = "trigger_expired",
                    )
                val ephemeralStub = mockk<SendSlackMessageEvent>(relaxed = true)
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralStub
                val queueSlot = slot<DefaultEventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(queueSlot)) } returns Unit

                service.onStandupModalOpenFailed(event = event)

                then("an ephemeral retry notice is published to the publisher") {
                    // chat.postEphemeral expects channel = D-channel ID and user = recipient
                    // user ID. Carrying both on CommandBasicInfo (channel + publisherId) and
                    // leaving targetUserId null keeps the two distinct on the wire instead of
                    // collapsing the user ID into the channel slot.
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = match { it.contains("Fill in standup") },
                            commandBasicInfo =
                                match {
                                    it.appId == "A_STANDUP" &&
                                        it.publisherId == "U_STANDUP" &&
                                        it.channel == "D_STANDUP" &&
                                        it.idempotencyKey == idempotencyKey
                                },
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            targetUserId = null,
                        )
                    }
                    queueSlot.isCaptured shouldBe true
                    queueSlot.captured.poll() shouldNotBe null
                }
            }
        }
    })
