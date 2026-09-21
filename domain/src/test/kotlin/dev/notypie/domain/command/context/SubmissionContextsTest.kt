package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.IgnoredSubmissionContext
import dev.notypie.domain.command.entity.context.form.AddParticipantParsed
import dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveSubscribeParsed
import dev.notypie.domain.command.entity.context.form.CveSubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeParsed
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.DeclineReasonParsed
import dev.notypie.domain.command.entity.context.form.DeclineReasonSubmissionContext
import dev.notypie.domain.command.entity.context.form.NoticeTarget
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingParsed
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupAnswerParsed
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupSetupParsed
import dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.entity.RejectReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class SubmissionContextsTest :
    BehaviorSpec({
        val interaction = createInboundInteraction()

        given("AddParticipantSubmissionContext with a parsed model") {
            val meetingUid = UUID.randomUUID()
            val intents = createIntentQueue()
            val context =
                AddParticipantSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intents,
                    model =
                        AddParticipantParsed(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_A", "U_B"),
                        ),
                )

            `when`("handleInteraction runs") {
                val output = context.handleInteraction(interaction = interaction)

                then("it succeeds and emits the AddParticipant intent") {
                    output.ok shouldBe true
                    output.commandDetailType shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                    val intent = intents.drainSnapshot().filterIsInstance<CommandIntent.AddParticipant>().single()
                    intent.meetingUid shouldBe meetingUid
                    intent.requesterId shouldBe "U_HOST"
                    intent.participantUserIds shouldContainExactly listOf("U_A", "U_B")
                }
            }
        }

        given("RescheduleMeetingSubmissionContext with a parsed model") {
            val meetingUid = UUID.randomUUID()
            val intents = createIntentQueue()
            val context =
                RescheduleMeetingSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intents,
                    model =
                        RescheduleMeetingParsed(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            newStartAt = LocalDateTime.of(2026, 10, 1, 14, 30),
                        ),
                )

            `when`("handleInteraction runs") {
                val output = context.handleInteraction(interaction = interaction)

                then("it succeeds and emits the RescheduleMeeting intent") {
                    output.ok shouldBe true
                    val intent = intents.drainSnapshot().filterIsInstance<CommandIntent.RescheduleMeeting>().single()
                    intent.meetingUid shouldBe meetingUid
                    intent.newStartAt shouldBe LocalDateTime.of(2026, 10, 1, 14, 30)
                }
            }
        }

        given("DeclineReasonSubmissionContext") {
            val key = UUID.randomUUID()

            fun model(notice: NoticeTarget) =
                DeclineReasonParsed(
                    meetingIdempotencyKey = key,
                    participantUserId = "U_P",
                    reason = RejectReason.OTHER,
                    reasonDetail = "family matters",
                    notice = notice,
                )

            `when`("the notice target is an Update") {
                val intents = createIntentQueue()
                val context =
                    DeclineReasonSubmissionContext(
                        commandBasicInfo = createCommandBasicInfo(),
                        intents = intents,
                        model = model(notice = NoticeTarget.Update(channel = "C_N", messageTs = "1.2")),
                    )
                val output = context.handleInteraction(interaction = interaction)

                then("the attendance intent and the notice update are both emitted, persistence first") {
                    output.ok shouldBe true
                    val effects = intents.drainSnapshot()
                    effects.indexOfFirst { it is CommandIntent.MeetingAttendanceUpdate } shouldBe 0
                    effects.indexOfFirst { it is OutboundMessage.UpdateMessage } shouldBe 1
                    val intent = effects.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    intent.meetingIdempotencyKey shouldBe key
                    intent.isAttending shouldBe false
                    intent.absentReason shouldBe RejectReason.OTHER
                    intent.absentReasonDetail shouldBe "family matters"
                    val update = effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe "C_N"
                    (update.content as MessageContent.Text).markdown shouldContain "family matters"
                }
            }

            `when`("the notice target is None") {
                val intents = createIntentQueue()
                val context =
                    DeclineReasonSubmissionContext(
                        commandBasicInfo = createCommandBasicInfo(),
                        intents = intents,
                        model = model(notice = NoticeTarget.None),
                    )
                context.handleInteraction(interaction = interaction)

                then("only the message update is suppressed") {
                    val effects = intents.drainSnapshot()
                    effects.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().shouldHaveSize(1)
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().shouldBeEmpty()
                }
            }
        }

        given("StandupAnswerSubmissionContext") {
            val sessionUid = UUID.randomUUID()

            fun model(responses: List<String>, notice: NoticeTarget) =
                StandupAnswerParsed(
                    sessionUid = sessionUid,
                    userId = "U_M",
                    responses = responses,
                    notice = notice,
                )

            `when`("responses are present with an Update notice") {
                val intents = createIntentQueue()
                StandupAnswerSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intents,
                    model =
                        model(
                            responses = listOf("a"),
                            notice = NoticeTarget.Update(channel = "C", messageTs = "1"),
                        ),
                ).handleInteraction(interaction = interaction)

                then("both the record intent and the update go out") {
                    val effects = intents.drainSnapshot()
                    effects.filterIsInstance<CommandIntent.RecordStandupAnswer>().shouldHaveSize(1)
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().shouldHaveSize(1)
                }
            }

            `when`("responses are empty but the notice is an Update") {
                val intents = createIntentQueue()
                StandupAnswerSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intents,
                    model =
                        model(
                            responses = emptyList(),
                            notice = NoticeTarget.Update(channel = "C", messageTs = "1"),
                        ),
                ).handleInteraction(interaction = interaction)

                then("persistence is suppressed but the update still goes out") {
                    val effects = intents.drainSnapshot()
                    effects.filterIsInstance<CommandIntent.RecordStandupAnswer>().shouldBeEmpty()
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().shouldHaveSize(1)
                }
            }
        }

        given("StandupSetupSubmissionContext with a parsed model") {
            val intents = createIntentQueue()
            val context =
                StandupSetupSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intents,
                    model =
                        StandupSetupParsed(
                            name = "daily",
                            creatorId = "U_C",
                            commandChannel = "C_CMD",
                            summaryChannel = "C_SUM",
                            questions = listOf("Q1"),
                            memberIds = listOf("U_A"),
                            weekdays = setOf(DayOfWeek.MONDAY),
                            triggerLocalTime = LocalTime.of(10, 0),
                            cutoffMinutes = 120L,
                            timezone = ZoneId.of("Asia/Seoul"),
                        ),
                )

            `when`("handleInteraction runs") {
                val output = context.handleInteraction(interaction = interaction)

                then("it emits the CreateStandupRoutine intent verbatim") {
                    output.ok shouldBe true
                    val intent = intents.drainSnapshot().filterIsInstance<CommandIntent.CreateStandupRoutine>().single()
                    intent.name shouldBe "daily"
                    intent.weekdays shouldBe setOf(DayOfWeek.MONDAY)
                }
            }
        }

        given("the CVE subscription contexts") {
            `when`("subscribe and unsubscribe models are accepted") {
                val subscribeIntents = createIntentQueue()
                CveSubscribeSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = subscribeIntents,
                    model = CveSubscribeParsed(userId = "U", topicKeys = listOf("spring")),
                ).handleInteraction(interaction = interaction)

                val unsubscribeIntents = createIntentQueue()
                CveUnsubscribeSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = unsubscribeIntents,
                    model = CveUnsubscribeParsed(userId = "U", topicKeys = emptyList()),
                ).handleInteraction(interaction = interaction)

                then("each emits its intent, empty key lists included") {
                    subscribeIntents
                        .drainSnapshot()
                        .filterIsInstance<CommandIntent.CveSubscribe>()
                        .single()
                        .topicKeys shouldContainExactly listOf("spring")
                    unsubscribeIntents
                        .drainSnapshot()
                        .filterIsInstance<CommandIntent.CveUnsubscribe>()
                        .single()
                        .topicKeys
                        .shouldBeEmpty()
                }
            }
        }

        given("IgnoredSubmissionContext") {
            val intents = createIntentQueue()
            val context =
                IgnoredSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intents,
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                )

            `when`("handleInteraction runs") {
                val output = context.handleInteraction(interaction = interaction)

                then("it succeeds with the routed detail type and queues nothing — not even a replacement message") {
                    output.ok shouldBe true
                    output.commandDetailType shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                    intents.drainSnapshot().shouldBeEmpty()
                }

                then("it is a plain success, not the ReactionContext default") {
                    context.shouldBeInstanceOf<IgnoredSubmissionContext>()
                }
            }
        }
    })
