package dev.notypie.domain.command.entity.slash

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MeetingListRangeTest :
    BehaviorSpec({
        val fixedNow = LocalDateTime.of(2026, 4, 20, 14, 30, 0)
        val startOfFixedDay = LocalDateTime.of(2026, 4, 20, 0, 0, 0)

        given("dateRange calculation") {
            `when`("TODAY range is requested") {
                val (startAt, endAt) = MeetingListRange.TODAY.dateRange(now = fixedNow)

                then("window is [startOfToday, startOfTomorrow)") {
                    startAt shouldBe startOfFixedDay
                    endAt shouldBe startOfFixedDay.plusDays(1L)
                }
            }

            `when`("TOMORROW range is requested") {
                val (startAt, endAt) = MeetingListRange.TOMORROW.dateRange(now = fixedNow)

                then("window is [startOfTomorrow, startOfDayAfterTomorrow)") {
                    startAt shouldBe startOfFixedDay.plusDays(1L)
                    endAt shouldBe startOfFixedDay.plusDays(2L)
                }
            }

            `when`("WEEK range is requested") {
                val (startAt, endAt) = MeetingListRange.WEEK.dateRange(now = fixedNow)

                then("window is [now, now + 7d)") {
                    startAt shouldBe fixedNow
                    endAt shouldBe fixedNow.plusDays(7L)
                }
            }

            `when`("MONTH range is requested") {
                val (startAt, endAt) = MeetingListRange.MONTH.dateRange(now = fixedNow)

                then("window is [now, now + 30d) — rolling 30 days, not calendar month") {
                    startAt shouldBe fixedNow
                    endAt shouldBe fixedNow.plusDays(30L)
                }
            }
        }

        given("parseOrNull token matching") {
            `when`("token exactly matches a lowercase identifier") {
                then("returns the matching enum") {
                    MeetingListRange.parseOrNull(token = "today") shouldBe MeetingListRange.TODAY
                    MeetingListRange.parseOrNull(token = "tomorrow") shouldBe MeetingListRange.TOMORROW
                    MeetingListRange.parseOrNull(token = "week") shouldBe MeetingListRange.WEEK
                    MeetingListRange.parseOrNull(token = "month") shouldBe MeetingListRange.MONTH
                }
            }

            `when`("token has surrounding whitespace") {
                then("trims and matches") {
                    MeetingListRange.parseOrNull(token = "  today  ") shouldBe MeetingListRange.TODAY
                }
            }

            `when`("token is uppercase or mixed case") {
                then("matches case-insensitively") {
                    MeetingListRange.parseOrNull(token = "TODAY") shouldBe MeetingListRange.TODAY
                    MeetingListRange.parseOrNull(token = "Week") shouldBe MeetingListRange.WEEK
                }
            }

            `when`("token is unknown") {
                then("returns null") {
                    MeetingListRange.parseOrNull(token = "yesterday").shouldBeNull()
                    MeetingListRange.parseOrNull(token = "bogus").shouldBeNull()
                }
            }

            `when`("token is empty or whitespace only") {
                then("returns null") {
                    MeetingListRange.parseOrNull(token = "").shouldBeNull()
                    MeetingListRange.parseOrNull(token = "   ").shouldBeNull()
                }
            }
        }

        given("default range") {
            `when`("DEFAULT is inspected") {
                then("equals WEEK") {
                    MeetingListRange.DEFAULT shouldBe MeetingListRange.WEEK
                }
            }
        }

        given("usage tokens display") {
            `when`("usageTokens is called") {
                then("joins all tokens with pipe separator in declaration order") {
                    MeetingListRange.usageTokens() shouldBe "today | tomorrow | week | month"
                }
            }
        }
    })
