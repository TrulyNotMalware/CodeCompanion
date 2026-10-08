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
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
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
private const val LIST_HANDLE = "https://hooks.slack.com/actions/T1/1/list"

class ParsedSubmissionsTest :
    BehaviorSpec({
        given("AddParticipantParsed.from") {
            val meetingUid = UUID.randomUUID()

            fun raw(
                uidRaw: String = meetingUid.toString(),
                requesterId: String = "U_HOST",
                idsRaw: String = "U_A,U_B",
                listHandleRaw: String = "",
            ) = InboundSubmission.AddParticipant(
                meetingUidRaw = uidRaw,
                requesterId = requesterId,
                participantUserIdsRaw = idsRaw,
                listHandleRaw = listHandleRaw,
            )

            `when`("the raw variant is well-formed") {
                val parsed = AddParticipantParsed.from(raw = raw(idsRaw = " U_A , U_B ,,"), actorId = ACTOR)

                then("ids are trimmed, blanks dropped, requester kept, and no list handle") {
                    parsed.shouldNotBeNull()
                    parsed.meetingUid shouldBe meetingUid
                    parsed.requesterId shouldBe "U_HOST"
                    parsed.participantUserIds shouldContainExactly listOf("U_A", "U_B")
                    parsed.listHandle shouldBe null
                }
            }

            `when`("the modal carried the list message's reply handle") {
                val parsed = AddParticipantParsed.from(raw = raw(listHandleRaw = LIST_HANDLE), actorId = ACTOR)

                then("it becomes the handle that closes the list") {
                    parsed.shouldNotBeNull().listHandle shouldBe ResponseReplaceHandle(raw = LIST_HANDLE)
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

            fun raw(
                uidRaw: String = meetingUid.toString(),
                date: String = "2026-10-01",
                time: String = "14:30",
                listHandleRaw: String = "",
            ) = InboundSubmission.RescheduleMeeting(
                meetingUidRaw = uidRaw,
                requesterId = "",
                date = date,
                time = time,
                listHandleRaw = listHandleRaw,
            )

            `when`("date and time parse to a start") {
                val parsed = RescheduleMeetingParsed.from(raw = raw(), actorId = ACTOR)

                then("they combine into the new start and the actor fills the blank requester") {
                    parsed.shouldNotBeNull()
                    parsed.newStartAt shouldBe LocalDateTime.of(2026, 10, 1, 14, 30)
                    parsed.requesterId shouldBe ACTOR
                    parsed.listHandle shouldBe null
                }
            }

            `when`("the modal carried the list message's reply handle") {
                val parsed = RescheduleMeetingParsed.from(raw = raw(listHandleRaw = LIST_HANDLE), actorId = ACTOR)

                then("it becomes the handle that closes the list") {
                    parsed.shouldNotBeNull().listHandle shouldBe ResponseReplaceHandle(raw = LIST_HANDLE)
                }
            }

            `when`("the uid, date, or time is unusable") {
                then("parsing rejects") {
                    RescheduleMeetingParsed.from(raw = raw(uidRaw = "nope"), actorId = ACTOR).shouldBeNull()
                    RescheduleMeetingParsed.from(raw = raw(date = ""), actorId = ACTOR).shouldBeNull()
                    RescheduleMeetingParsed.from(raw = raw(time = "25:99"), actorId = ACTOR).shouldBeNull()
                }
            }

            `when`("the new start is in the past") {
                val parsed =
                    RescheduleMeetingParsed.from(raw = raw(date = "2000-01-01", time = "09:00"), actorId = ACTOR)

                then("parsing keeps it so the reschedule service can tell the host to pick a future time") {
                    parsed.shouldNotBeNull()
                    parsed.newStartAt shouldBe LocalDateTime.of(2000, 1, 1, 9, 0)
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

            `when`("an OTHER detail carries Slack control characters") {
                val parsed =
                    DeclineReasonParsed.from(
                        raw = raw(reasonRaw = "OTHER", detailRaw = "<https://evil.example|Zoom> <!here> & co"),
                        actorId = ACTOR,
                    )

                then("the stored detail is raw and the notice markdown is escaped") {
                    parsed.shouldNotBeNull().reasonDetail shouldBe "<https://evil.example|Zoom> <!here> & co"
                    parsed.noticeSummaryMarkdown() shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.OTHER.showMessage} — " +
                        "&lt;https://evil.example|Zoom&gt; &lt;!here&gt; &amp; co"
                }
            }

            `when`("an OTHER detail is exactly the stored length, or one character longer") {
                val atLimit = "a".repeat(RejectReason.MAX_DETAIL_LENGTH)
                val fits =
                    DeclineReasonParsed.from(raw = raw(reasonRaw = "OTHER", detailRaw = " $atLimit "), actorId = ACTOR)
                val tooLong =
                    DeclineReasonParsed.from(raw = raw(reasonRaw = "OTHER", detailRaw = atLimit + "b"), actorId = ACTOR)

                then("the fitting note is kept (trimmed before measuring)") {
                    fits.shouldNotBeNull().reasonDetail shouldBe atLimit
                    fits.detailTooLong shouldBe false
                }

                then("the longer note is dropped, the decline still parses as OTHER and its notice says why") {
                    tooLong.shouldNotBeNull()
                    tooLong.reason shouldBe RejectReason.OTHER
                    tooLong.reasonDetail.shouldBeNull()
                    tooLong.detailTooLong shouldBe true
                    tooLong.noticeSummaryMarkdown() shouldBe
                        "You declined the meeting — *Reason:* ${RejectReason.OTHER.showMessage} — " +
                        "_Your note was longer than 255 characters and was not saved._"
                }
            }

            `when`("an OTHER detail of emoji is at the stored length in code points, or one past it") {
                val emoji = "😀"
                val atLimit = emoji.repeat(RejectReason.MAX_DETAIL_LENGTH)
                val fits =
                    DeclineReasonParsed.from(
                        raw = raw(reasonRaw = "OTHER", detailRaw = atLimit),
                        actorId = ACTOR,
                    )
                val tooLong =
                    DeclineReasonParsed.from(
                        raw = raw(reasonRaw = "OTHER", detailRaw = atLimit + emoji),
                        actorId = ACTOR,
                    )

                then("the length is measured in code points, the unit utf8mb4 VARCHAR counts") {
                    fits.shouldNotBeNull().reasonDetail shouldBe atLimit
                    fits.detailTooLong shouldBe false
                    tooLong.shouldNotBeNull().reasonDetail.shouldBeNull()
                    tooLong.detailTooLong shouldBe true
                }
            }

            `when`("a non-OTHER reason arrives with a long detail") {
                val parsed =
                    DeclineReasonParsed.from(
                        raw = raw(reasonRaw = "VACATION", detailRaw = "a".repeat(RejectReason.MAX_DETAIL_LENGTH + 1)),
                        actorId = ACTOR,
                    )

                then("the detail is ignored as before, so nothing is flagged") {
                    parsed.shouldNotBeNull().reasonDetail.shouldBeNull()
                    parsed.detailTooLong shouldBe false
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
                    parsed.timezone shouldBe ZoneId.of("Asia/Seoul")
                }

                then("a non-numeric cutoff is flagged as invalid instead of silently becoming the default") {
                    parsed.cutoffMinutes.shouldBeNull()
                }
            }

            `when`("the cutoff is blank") {
                val parsed = StandupSetupParsed.from(raw = raw(cutoffRaw = "  "), actorId = ACTOR)

                then("the modal's default is used") {
                    parsed.cutoffMinutes shouldBe StandupSetupParsed.DEFAULT_CUTOFF_MINUTES
                }
            }

            `when`("the cutoff is a whole number outside the Routine bounds") {
                then("zero, a negative value, a value past one day and an overflowing value are all flagged") {
                    listOf("0", "-5", "1441", "1000000000000000", "99999999999999999999").forEach { cutoff ->
                        StandupSetupParsed
                            .from(raw = raw(cutoffRaw = cutoff), actorId = ACTOR)
                            .cutoffMinutes
                            .shouldBeNull()
                    }
                }
            }

            `when`("the cutoff sits on the Routine bounds") {
                then("1 and 1440 minutes are accepted") {
                    StandupSetupParsed.from(raw = raw(cutoffRaw = "1"), actorId = ACTOR).cutoffMinutes shouldBe 1L
                    StandupSetupParsed.from(raw = raw(cutoffRaw = "1440"), actorId = ACTOR).cutoffMinutes shouldBe
                        1440L
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
