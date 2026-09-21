package dev.notypie.domain.command

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.entity.RejectReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private fun execute(
    detailType: CommandDetailType,
    submission: InboundSubmission?,
): Pair<CommandOutput, List<CommandEffect>> {
    val interaction =
        createInboundInteraction(
            detailType = detailType,
            action = approveAction(isSelected = true),
            submission = submission,
        )
    val command =
        InteractionCommand(
            appName = "test-app",
            idempotencyKey = UUID.randomUUID(),
            commandData = createInteractionResponseInboundCommand(interaction = interaction),
            actorRole = UserRole.USER,
        )
    val output = command.handleEvent()
    return output to command.drainIntents()
}

class SubmissionPipelineCharacterizationTest :
    BehaviorSpec({
        given("MEETING_ADD_PARTICIPANT_SUBMIT") {
            `when`("the submission is valid") {
                val meetingUid = UUID.randomUUID()
                val (output, effects) =
                    execute(
                        detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                        submission =
                            InboundSubmission.AddParticipant(
                                meetingUidRaw = meetingUid.toString(),
                                requesterId = "U_HOST",
                                participantUserIdsRaw = " U_A , U_B ,,",
                            ),
                    )

                then("one AddParticipant intent carries the parsed fields, ids trimmed and blanks dropped") {
                    output.ok shouldBe true
                    output.commandDetailType shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                    val intent = effects.filterIsInstance<CommandIntent.AddParticipant>().single()
                    intent.meetingUid shouldBe meetingUid
                    intent.requesterId shouldBe "U_HOST"
                    intent.participantUserIds shouldContainExactly listOf("U_A", "U_B")
                }
            }

            `when`("the requester routing token is blank") {
                val (_, effects) =
                    execute(
                        detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                        submission =
                            InboundSubmission.AddParticipant(
                                meetingUidRaw = UUID.randomUUID().toString(),
                                requesterId = "",
                                participantUserIdsRaw = "U_A",
                            ),
                    )

                then("the submitting actor becomes the requester") {
                    effects.filterIsInstance<CommandIntent.AddParticipant>().single().requesterId shouldBe TEST_USER_ID
                }
            }

            `when`("the meeting uid is malformed or the selection is empty") {
                val badUid =
                    execute(
                        detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                        submission =
                            InboundSubmission.AddParticipant(
                                meetingUidRaw = "not-a-uuid",
                                requesterId = "U_HOST",
                                participantUserIdsRaw = "U_A",
                            ),
                    )
                val emptySelection =
                    execute(
                        detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                        submission =
                            InboundSubmission.AddParticipant(
                                meetingUidRaw = UUID.randomUUID().toString(),
                                requesterId = "U_HOST",
                                participantUserIdsRaw = " , ",
                            ),
                    )

                then("both fall open: success with no effects") {
                    badUid.first.ok shouldBe true
                    badUid.second.shouldBeEmpty()
                    emptySelection.first.ok shouldBe true
                    emptySelection.second.shouldBeEmpty()
                }
            }

            `when`("the SUBMIT detail type arrives with no submission payload") {
                val (output, effects) =
                    execute(
                        detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                        submission = null,
                    )

                then("it falls open: success, detail type preserved, no effects") {
                    output.ok shouldBe true
                    output.commandDetailType shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                    effects.shouldBeEmpty()
                }
            }
        }

        given("MEETING_RESCHEDULE_SUBMIT") {
            `when`("the submission carries a parseable date and time") {
                val meetingUid = UUID.randomUUID()
                val (output, effects) =
                    execute(
                        detailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                        submission =
                            InboundSubmission.RescheduleMeeting(
                                meetingUidRaw = meetingUid.toString(),
                                requesterId = "U_HOST",
                                date = "2026-10-01",
                                time = "14:30",
                            ),
                    )

                then("one RescheduleMeeting intent carries the combined start datetime") {
                    output.ok shouldBe true
                    val intent = effects.filterIsInstance<CommandIntent.RescheduleMeeting>().single()
                    intent.meetingUid shouldBe meetingUid
                    intent.requesterId shouldBe "U_HOST"
                    intent.newStartAt shouldBe LocalDateTime.of(2026, 10, 1, 14, 30)
                }
            }

            `when`("the uid, date, or time is unusable") {
                val cases =
                    listOf(
                        InboundSubmission.RescheduleMeeting(
                            meetingUidRaw = "broken",
                            requesterId = "U_HOST",
                            date = "2026-10-01",
                            time = "14:30",
                        ),
                        InboundSubmission.RescheduleMeeting(
                            meetingUidRaw = UUID.randomUUID().toString(),
                            requesterId = "U_HOST",
                            date = "",
                            time = "14:30",
                        ),
                        InboundSubmission.RescheduleMeeting(
                            meetingUidRaw = UUID.randomUUID().toString(),
                            requesterId = "U_HOST",
                            date = "2026-10-01",
                            time = "25:99",
                        ),
                    )

                then("each falls open: success with no effects") {
                    cases.forEach { submission ->
                        val (output, effects) =
                            execute(
                                detailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                                submission = submission,
                            )
                        output.ok shouldBe true
                        effects.shouldBeEmpty()
                    }
                }
            }
        }

        given("MEETING_DECLINE_REASON") {
            val meetingKey = UUID.randomUUID()

            fun decline(
                reasonRaw: String,
                detailRaw: String = "",
                noticeChannel: String = "C_NOTICE",
                noticeMessageTs: String = "123.456",
                keyRaw: String = meetingKey.toString(),
            ) = execute(
                detailType = CommandDetailType.MEETING_DECLINE_REASON,
                submission =
                    InboundSubmission.DeclineReason(
                        meetingIdempotencyKeyRaw = keyRaw,
                        participantUserId = "U_PART",
                        noticeChannel = noticeChannel,
                        noticeMessageTs = noticeMessageTs,
                        reasonRaw = reasonRaw,
                        detailRaw = detailRaw,
                    ),
            )

            `when`("a known reason is submitted with notice routing") {
                val (output, effects) = decline(reasonRaw = "SCHEDULE_CONFLICT")

                then("the attendance intent and the notice update are both emitted") {
                    output.ok shouldBe true
                    val intent = effects.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    intent.meetingIdempotencyKey shouldBe meetingKey
                    intent.participantUserId shouldBe "U_PART"
                    intent.isAttending shouldBe false
                    intent.absentReason shouldBe RejectReason.SCHEDULE_CONFLICT
                    intent.absentReasonDetail.shouldBeNull()
                    val update = effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe "C_NOTICE"
                    update.ref.messageId shouldBe "123.456"
                }
            }

            `when`("the reason token is unknown or ATTENDING") {
                val unknown = decline(reasonRaw = "NOT_A_REASON")
                val attending = decline(reasonRaw = "ATTENDING")

                then("both collapse to OTHER") {
                    unknown.second
                        .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                        .single()
                        .absentReason shouldBe RejectReason.OTHER
                    attending.second
                        .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                        .single()
                        .absentReason shouldBe RejectReason.OTHER
                }
            }

            `when`("OTHER is submitted with detail, and with blank detail") {
                val withDetail = decline(reasonRaw = "OTHER", detailRaw = " family matters ")
                val blankDetail = decline(reasonRaw = "OTHER", detailRaw = "  ")

                then("the detail is trimmed and kept; a blank detail survives as an empty string") {
                    withDetail.second
                        .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                        .single()
                        .absentReasonDetail shouldBe "family matters"
                    blankDetail.second
                        .filterIsInstance<CommandIntent.MeetingAttendanceUpdate>()
                        .single()
                        .absentReasonDetail shouldBe ""
                    withDetail.second
                        .filterIsInstance<OutboundMessage.UpdateMessage>()
                        .single()
                        .let { it.content }
                        .let { it as dev.notypie.domain.command.outbound.MessageContent.Text }
                        .markdown shouldContain "family matters"
                }
            }

            `when`("the notice routing is absent") {
                val (output, effects) = decline(reasonRaw = "VACATION", noticeChannel = "", noticeMessageTs = "")

                then("only the message update is suppressed; the attendance intent still persists") {
                    output.ok shouldBe true
                    effects.filterIsInstance<CommandIntent.MeetingAttendanceUpdate>().single()
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().shouldBeEmpty()
                }
            }

            `when`("the meeting idempotency key is malformed") {
                val (output, effects) = decline(reasonRaw = "VACATION", keyRaw = "broken")

                then("it falls open: success with no effects at all") {
                    output.ok shouldBe true
                    effects.shouldBeEmpty()
                }
            }
        }

        given("STANDUP_ANSWER_SUBMIT") {
            val sessionUid = UUID.randomUUID()

            fun answer(
                answers: List<String>,
                userId: String = "U_MEMBER",
                noticeChannel: String = "C_STANDUP",
                noticeMessageTs: String = "777.888",
                uidRaw: String = sessionUid.toString(),
            ) = execute(
                detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                submission =
                    InboundSubmission.StandupAnswer(
                        sessionUidRaw = uidRaw,
                        userId = userId,
                        noticeChannel = noticeChannel,
                        noticeMessageTs = noticeMessageTs,
                        answers = answers,
                    ),
            )

            `when`("answers are present with notice routing") {
                val (output, effects) = answer(answers = listOf("did X", "will do Y"))

                then("the record intent and the notice update are both emitted") {
                    output.ok shouldBe true
                    val intent = effects.filterIsInstance<CommandIntent.RecordStandupAnswer>().single()
                    intent.sessionUid shouldBe sessionUid
                    intent.userId shouldBe "U_MEMBER"
                    intent.responses shouldContainExactly listOf("did X", "will do Y")
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                }
            }

            `when`("the answer list is empty but notice routing is valid") {
                val (output, effects) = answer(answers = emptyList())

                then("persistence is suppressed but the notice update is still emitted") {
                    output.ok shouldBe true
                    effects.filterIsInstance<CommandIntent.RecordStandupAnswer>().shouldBeEmpty()
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                }
            }

            `when`("the routed user id is blank") {
                val (_, effects) = answer(answers = listOf("did X"), userId = "")

                then("the submitting actor becomes the answering user") {
                    effects.filterIsInstance<CommandIntent.RecordStandupAnswer>().single().userId shouldBe TEST_USER_ID
                }
            }

            `when`("the session uid is malformed") {
                val (output, effects) = answer(answers = listOf("did X"), uidRaw = "broken")

                then("it falls open: success with no effects, notice update included") {
                    output.ok shouldBe true
                    effects.shouldBeEmpty()
                }
            }
        }

        given("STANDUP_SETUP_SUBMIT") {
            fun setup(
                weekdaysRaw: String = "MONDAY,TUESDAY",
                timeRaw: String = "09:30",
                cutoffRaw: String = "90",
                timezoneRaw: String = "UTC",
                creatorId: String = "U_CREATOR",
            ) = execute(
                detailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                submission =
                    InboundSubmission.StandupSetup(
                        idempotencyKeyRaw = UUID.randomUUID().toString(),
                        creatorId = creatorId,
                        commandChannel = "C_CMD",
                        name = " daily sync ",
                        questionsRaw = "Q1\n \nQ2\n",
                        membersRaw = "U_A, U_B,,",
                        summaryChannel = " C_SUMMARY ",
                        weekdaysRaw = weekdaysRaw,
                        timeRaw = timeRaw,
                        cutoffRaw = cutoffRaw,
                        timezoneRaw = timezoneRaw,
                    ),
            )

            `when`("every field parses") {
                val (output, effects) = setup()

                then("one CreateStandupRoutine intent carries the trimmed, split, parsed fields") {
                    output.ok shouldBe true
                    val intent = effects.filterIsInstance<CommandIntent.CreateStandupRoutine>().single()
                    intent.name shouldBe "daily sync"
                    intent.creatorId shouldBe "U_CREATOR"
                    intent.commandChannel shouldBe "C_CMD"
                    intent.summaryChannel shouldBe "C_SUMMARY"
                    intent.questions shouldContainExactly listOf("Q1", "Q2")
                    intent.memberIds shouldContainExactly listOf("U_A", "U_B")
                    intent.weekdays shouldBe setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
                    intent.triggerLocalTime shouldBe LocalTime.of(9, 30)
                    intent.cutoffMinutes shouldBe 90L
                    intent.timezone shouldBe ZoneId.of("UTC")
                }
            }

            `when`("schedule tokens are unusable and the creator token is blank") {
                val (_, effects) =
                    setup(
                        weekdaysRaw = "MONDAY,NOTADAY",
                        timeRaw = "quarter past",
                        cutoffRaw = "soon",
                        timezoneRaw = "Mars/Olympus",
                        creatorId = "",
                    )

                then("bad weekdays are dropped and the rest fall back to defaults, still emitting the intent") {
                    val intent = effects.filterIsInstance<CommandIntent.CreateStandupRoutine>().single()
                    intent.weekdays shouldBe setOf(DayOfWeek.MONDAY)
                    intent.triggerLocalTime shouldBe LocalTime.of(10, 0)
                    intent.cutoffMinutes shouldBe 120L
                    intent.timezone shouldBe ZoneId.of("Asia/Seoul")
                    intent.creatorId shouldBe TEST_USER_ID
                }
            }
        }

        given("CVE_SUBSCRIBE_SUBMIT and CVE_UNSUBSCRIBE_SUBMIT") {
            `when`("topics are selected") {
                val (output, effects) =
                    execute(
                        detailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                        submission = InboundSubmission.CveSubscribe(topicKeys = listOf("spring", "kafka")),
                    )

                then("the subscribe intent is keyed to the submitting actor") {
                    output.ok shouldBe true
                    val intent = effects.filterIsInstance<CommandIntent.CveSubscribe>().single()
                    intent.userId shouldBe TEST_USER_ID
                    intent.topicKeys shouldContainExactly listOf("spring", "kafka")
                }
            }

            `when`("the topic selection is empty") {
                val subscribe =
                    execute(
                        detailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                        submission = InboundSubmission.CveSubscribe(topicKeys = emptyList()),
                    )
                val unsubscribe =
                    execute(
                        detailType = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
                        submission = InboundSubmission.CveUnsubscribe(topicKeys = emptyList()),
                    )

                then("an intent is still emitted with the empty list — the service resolves it downstream") {
                    subscribe.second
                        .filterIsInstance<CommandIntent.CveSubscribe>()
                        .single()
                        .topicKeys
                        .shouldBeEmpty()
                    unsubscribe.second
                        .filterIsInstance<CommandIntent.CveUnsubscribe>()
                        .single()
                        .topicKeys
                        .shouldBeEmpty()
                }
            }
        }

        given("a SUBMIT detail type paired with a foreign submission variant") {
            `when`("CVE_SUBSCRIBE_SUBMIT carries an AddParticipant submission") {
                val meetingUid = UUID.randomUUID()
                val (output, effects) =
                    execute(
                        detailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                        submission =
                            InboundSubmission.AddParticipant(
                                meetingUidRaw = meetingUid.toString(),
                                requesterId = "U_HOST",
                                participantUserIdsRaw = "U_A",
                            ),
                    )

                then("the variant wins: the submission executes as its own flow") {
                    output.ok shouldBe true
                    output.commandDetailType shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                    effects.filterIsInstance<CommandIntent.AddParticipant>().single().meetingUid shouldBe meetingUid
                }
            }
        }
    })
