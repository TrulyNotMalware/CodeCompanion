package dev.notypie.domain.command.context

import dev.notypie.domain.command.entity.context.form.AddParticipantParsed
import dev.notypie.domain.command.entity.context.form.CveSubscribeParsed
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeParsed
import dev.notypie.domain.command.entity.context.form.DeclineReasonParsed
import dev.notypie.domain.command.entity.context.form.NoticeTarget
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingParsed
import dev.notypie.domain.command.entity.context.form.StandupAnswerParsed
import dev.notypie.domain.command.entity.context.form.StandupSetupParsed
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.meet.entity.RejectReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private const val ACTOR = "U_ACTOR"

class ParsedSubmissionsTest :
    BehaviorSpec({
        given("AddParticipantParsed.from") {
            val meetingUid = UUID.randomUUID()

            fun raw(
                uidRaw: String = meetingUid.toString(),
                requesterId: String = "U_HOST",
                idsRaw: String = "U_A,U_B",
            ) = InboundSubmission.AddParticipant(
                meetingUidRaw = uidRaw,
                requesterId = requesterId,
                participantUserIdsRaw = idsRaw,
            )

            `when`("the raw variant is well-formed") {
                val parsed = AddParticipantParsed.from(raw = raw(idsRaw = " U_A , U_B ,,"), actorId = ACTOR)

                then("ids are trimmed, blanks dropped, requester kept") {
                    parsed.shouldNotBeNull()
                    parsed.meetingUid shouldBe meetingUid
                    parsed.requesterId shouldBe "U_HOST"
                    parsed.participantUserIds shouldContainExactly listOf("U_A", "U_B")
                }
            }

            `when`("the requester token is blank") {
                val parsed = AddParticipantParsed.from(raw = raw(requesterId = " "), actorId = ACTOR)

                then("the actor becomes the requester") {
                    parsed.shouldNotBeNull().requesterId shouldBe ACTOR
                }
            }

            `when`("the uid is malformed or no participant survives the split") {
                then("parsing rejects") {
                    AddParticipantParsed.from(raw = raw(uidRaw = "nope"), actorId = ACTOR).shouldBeNull()
                    AddParticipantParsed.from(raw = raw(idsRaw = " , "), actorId = ACTOR).shouldBeNull()
                }
            }
        }

        given("RescheduleMeetingParsed.from") {
            val meetingUid = UUID.randomUUID()

            fun raw(uidRaw: String = meetingUid.toString(), date: String = "2026-10-01", time: String = "14:30") =
                InboundSubmission.RescheduleMeeting(
                    meetingUidRaw = uidRaw,
                    requesterId = "",
                    date = date,
                    time = time,
                )

            `when`("date and time parse") {
                val parsed = RescheduleMeetingParsed.from(raw = raw(), actorId = ACTOR)

                then("they combine into the new start and the actor fills the blank requester") {
                    parsed.shouldNotBeNull()
                    parsed.newStartAt shouldBe LocalDateTime.of(2026, 10, 1, 14, 30)
                    parsed.requesterId shouldBe ACTOR
                }
            }

            `when`("the uid, date, or time is unusable") {
                then("parsing rejects") {
                    RescheduleMeetingParsed.from(raw = raw(uidRaw = "nope"), actorId = ACTOR).shouldBeNull()
                    RescheduleMeetingParsed.from(raw = raw(date = ""), actorId = ACTOR).shouldBeNull()
                    RescheduleMeetingParsed.from(raw = raw(time = "25:99"), actorId = ACTOR).shouldBeNull()
                }
            }
        }

        given("DeclineReasonParsed.from") {
            val key = UUID.randomUUID()

            fun raw(
                keyRaw: String = key.toString(),
                reasonRaw: String = "SCHEDULE_CONFLICT",
                detailRaw: String = "",
                channel: String = "C_N",
                ts: String = "1.2",
            ) = InboundSubmission.DeclineReason(
                meetingIdempotencyKeyRaw = keyRaw,
                participantUserId = "U_P",
                noticeChannel = channel,
                noticeMessageTs = ts,
                reasonRaw = reasonRaw,
                detailRaw = detailRaw,
            )

            `when`("a known reason arrives with notice routing") {
                val parsed = DeclineReasonParsed.from(raw = raw(), actorId = ACTOR)

                then("reason maps, detail stays null, notice is an Update") {
                    parsed.shouldNotBeNull()
                    parsed.reason shouldBe RejectReason.SCHEDULE_CONFLICT
                    parsed.reasonDetail.shouldBeNull()
                    parsed.notice shouldBe NoticeTarget.Update(channel = "C_N", messageTs = "1.2")
                }
            }

            `when`("the reason token is unknown or ATTENDING") {
                then("both collapse to OTHER") {
                    DeclineReasonParsed
                        .from(raw = raw(reasonRaw = "NOT_A_REASON"), actorId = ACTOR)
                        .shouldNotBeNull()
                        .reason shouldBe RejectReason.OTHER
                    DeclineReasonParsed
                        .from(raw = raw(reasonRaw = "ATTENDING"), actorId = ACTOR)
                        .shouldNotBeNull()
                        .reason shouldBe RejectReason.OTHER
                }
            }

            `when`("OTHER carries a detail, or a blank detail") {
                val withDetail =
                    DeclineReasonParsed.from(
                        raw = raw(reasonRaw = "OTHER", detailRaw = " x "),
                        actorId = ACTOR,
                    )
                val blank = DeclineReasonParsed.from(raw = raw(reasonRaw = "OTHER", detailRaw = " "), actorId = ACTOR)

                then("the detail is trimmed; a blank one survives as an empty string") {
                    withDetail.shouldNotBeNull().reasonDetail shouldBe "x"
                    withDetail.noticeSummaryMarkdown() shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.OTHER.showMessage} — x"
                    blank.shouldNotBeNull().reasonDetail shouldBe ""
                    blank.noticeSummaryMarkdown() shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.OTHER.showMessage}"
                }
            }

            `when`("notice routing is partial or the key is malformed") {
                then("partial routing becomes None; a bad key rejects") {
                    DeclineReasonParsed
                        .from(raw = raw(ts = ""), actorId = ACTOR)
                        .shouldNotBeNull()
                        .notice shouldBe NoticeTarget.None
                    DeclineReasonParsed.from(raw = raw(keyRaw = "nope"), actorId = ACTOR).shouldBeNull()
                }
            }

            `when`("the participant routing token is blank") {
                val declineRaw =
                    InboundSubmission.DeclineReason(
                        meetingIdempotencyKeyRaw = key.toString(),
                        participantUserId = " ",
                        noticeChannel = "",
                        noticeMessageTs = "",
                        reasonRaw = "VACATION",
                        detailRaw = "",
                    )

                then("the actor fills it; a blank actor stays blank (the wire permits an empty actor id)") {
                    DeclineReasonParsed
                        .from(raw = declineRaw, actorId = ACTOR)
                        .shouldNotBeNull()
                        .participantUserId shouldBe ACTOR
                    DeclineReasonParsed
                        .from(raw = declineRaw, actorId = " ")
                        .shouldNotBeNull()
                        .participantUserId shouldBe " "
                }
            }
        }

        given("StandupAnswerParsed.from") {
            val sessionUid = UUID.randomUUID()

            `when`("the session uid parses") {
                val parsed =
                    StandupAnswerParsed.from(
                        raw =
                            InboundSubmission.StandupAnswer(
                                sessionUidRaw = sessionUid.toString(),
                                userId = "",
                                noticeChannel = "C_S",
                                noticeMessageTs = "9.9",
                                answers = emptyList(),
                            ),
                        actorId = ACTOR,
                    )

                then("empty answers are kept (not a rejection) and the actor fills the blank user") {
                    parsed.shouldNotBeNull()
                    parsed.responses.shouldBeEmpty()
                    parsed.userId shouldBe ACTOR
                    parsed.notice shouldBe NoticeTarget.Update(channel = "C_S", messageTs = "9.9")
                }
            }

            `when`("the session uid is malformed") {
                then("parsing rejects") {
                    StandupAnswerParsed
                        .from(
                            raw =
                                InboundSubmission.StandupAnswer(
                                    sessionUidRaw = "nope",
                                    userId = "U",
                                    noticeChannel = "",
                                    noticeMessageTs = "",
                                    answers = listOf("a"),
                                ),
                            actorId = ACTOR,
                        ).shouldBeNull()
                }
            }
        }

        given("StandupSetupParsed.from") {
            fun raw(
                weekdaysRaw: String = "MONDAY,NOTADAY",
                timeRaw: String = "bad",
                cutoffRaw: String = "bad",
                timezoneRaw: String = "bad",
            ) = InboundSubmission.StandupSetup(
                idempotencyKeyRaw = UUID.randomUUID().toString(),
                creatorId = "",
                commandChannel = "C_CMD",
                name = " n ",
                questionsRaw = "Q1\n\nQ2",
                membersRaw = "U_A,,U_B",
                summaryChannel = " C_SUM ",
                weekdaysRaw = weekdaysRaw,
                timeRaw = timeRaw,
                cutoffRaw = cutoffRaw,
                timezoneRaw = timezoneRaw,
            )

            `when`("schedule tokens are partially unusable") {
                val parsed = StandupSetupParsed.from(raw = raw(), actorId = ACTOR)

                then("it never rejects: bad weekdays drop, the rest default, text fields trim and split") {
                    parsed.name shouldBe "n"
                    parsed.creatorId shouldBe ACTOR
                    parsed.questions shouldContainExactly listOf("Q1", "Q2")
                    parsed.memberIds shouldContainExactly listOf("U_A", "U_B")
                    parsed.summaryChannel shouldBe "C_SUM"
                    parsed.weekdays shouldBe setOf(DayOfWeek.MONDAY)
                    parsed.triggerLocalTime shouldBe LocalTime.of(10, 0)
                    parsed.cutoffMinutes shouldBe StandupSetupParsed.DEFAULT_CUTOFF_MINUTES
                    parsed.timezone shouldBe ZoneId.of("Asia/Seoul")
                }
            }

            `when`("every token parses") {
                val parsed =
                    StandupSetupParsed.from(
                        raw = raw(weekdaysRaw = "FRIDAY", timeRaw = "09:15", cutoffRaw = " 45 ", timezoneRaw = " UTC "),
                        actorId = ACTOR,
                    )

                then("the parsed values are used") {
                    parsed.weekdays shouldBe setOf(DayOfWeek.FRIDAY)
                    parsed.triggerLocalTime shouldBe LocalTime.of(9, 15)
                    parsed.cutoffMinutes shouldBe 45L
                    parsed.timezone shouldBe ZoneId.of("UTC")
                }
            }
        }

        given("CveSubscribeParsed.from and CveUnsubscribeParsed.from") {
            `when`("any selection arrives, including an empty one") {
                then("parsing never rejects and keys to the actor") {
                    val subscribe =
                        CveSubscribeParsed.from(
                            raw = InboundSubmission.CveSubscribe(topicKeys = emptyList()),
                            actorId = ACTOR,
                        )
                    subscribe.userId shouldBe ACTOR
                    subscribe.topicKeys.shouldBeEmpty()

                    val unsubscribe =
                        CveUnsubscribeParsed.from(
                            raw = InboundSubmission.CveUnsubscribe(topicKeys = listOf("spring")),
                            actorId = ACTOR,
                        )
                    unsubscribe.userId shouldBe ACTOR
                    unsubscribe.topicKeys shouldContainExactly listOf("spring")
                }
            }
        }
    })
