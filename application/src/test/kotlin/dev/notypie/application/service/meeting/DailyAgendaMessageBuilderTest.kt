package dev.notypie.application.service.meeting

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.SlackApiEventConstructor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class DailyAgendaMessageBuilderTest :
    BehaviorSpec({
        given("buildAgendaDm") {
            `when`("a user has multiple meetings supplied out of order") {
                val slackEventBuilder = mockk<SlackApiEventConstructor>()
                val basicInfo = createCommandBasicInfo()
                val agendaDate = LocalDate.of(2026, 5, 4)
                val meetings =
                    listOf(
                        createAgendaItem(
                            startAt = LocalDateTime.of(2026, 5, 4, 14, 0),
                            title = "1:1 with Lead",
                        ),
                        createAgendaItem(
                            startAt = LocalDateTime.of(2026, 5, 4, 10, 0),
                            title = "Sprint Planning",
                        ),
                    )

                val capturedHeadline = slot<String>()
                val capturedBody = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = capture(capturedHeadline),
                        commandBasicInfo = any(),
                        simpleString = capture(capturedBody),
                    )
                } returns
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.DAILY_AGENDA,
                        idempotencyKey = basicInfo.idempotencyKey,
                    )

                buildAgendaDm(
                    slackEventBuilder = slackEventBuilder,
                    agendaDate = agendaDate,
                    meetings = meetings,
                    commandBasicInfo = basicInfo,
                )

                then("the headline is the date header") {
                    capturedHeadline.captured shouldBe "🗓️ Today's meetings (2026-05-04)"
                }

                then("the body lists meetings sorted by start time, one line each") {
                    capturedBody.captured shouldBe "• 10:00 — Sprint Planning\n• 14:00 — 1:1 with Lead"
                }

                then("it delegates to a plain-text request typed DAILY_AGENDA") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.DAILY_AGENDA,
                            headLineText = any(),
                            commandBasicInfo = basicInfo,
                            simpleString = any(),
                        )
                    }
                }
            }
        }
    })
