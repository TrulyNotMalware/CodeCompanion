package dev.notypie.application.service.meeting

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

class DailyAgendaMessageBuilderTest :
    BehaviorSpec({
        given("buildAgendaDm") {
            `when`("a user has multiple meetings supplied out of order") {
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

                val channelMessage =
                    buildAgendaDm(
                        agendaDate = agendaDate,
                        meetings = meetings,
                        commandBasicInfo = basicInfo,
                    )
                val text = channelMessage.content as MessageContent.Text

                then("the headline is the date header") {
                    text.headline shouldBe "🗓️ Today's meetings (2026-05-04)"
                }

                then("the body lists meetings sorted by start time, one line each") {
                    text.markdown shouldBe "• 10:00 — Sprint Planning\n• 14:00 — 1:1 with Lead"
                }

                then("it builds a plain-text ChannelMessage typed DAILY_AGENDA to the command channel") {
                    channelMessage.detailType shouldBe CommandDetailType.DAILY_AGENDA
                    channelMessage.target shouldBe ConversationTarget(id = basicInfo.channel)
                }
            }
        }
    })
