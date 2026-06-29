package dev.notypie.application.service.standup

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.SlackApiEventConstructor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class StandupDispatchMessageBuilderTest :
    BehaviorSpec({
        given("buildDmNotice") {
            `when`("called with primitive routing fields") {
                val slackEventBuilder = mockk<SlackApiEventConstructor>()
                val builder = StandupDispatchMessageBuilder(slackEventBuilder = slackEventBuilder)
                val basicInfo = createCommandBasicInfo()
                val routineUid = UUID.randomUUID()
                val sessionUid = UUID.randomUUID()
                val sessionDate = LocalDate.of(2026, 5, 4)

                val capturedContents = slot<ApprovalContents>()
                val capturedExtras = slot<List<String>>()
                val capturedTargetUser = slot<String>()
                every {
                    slackEventBuilder.simpleApplyRejectRequest(
                        commandDetailType = any(),
                        commandBasicInfo = any(),
                        approvalContents = capture(capturedContents),
                        targetUserId = capture(capturedTargetUser),
                        routingExtras = capture(capturedExtras),
                    )
                } returns
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.STANDUP_FILL,
                        idempotencyKey = basicInfo.idempotencyKey,
                    )

                builder.buildDmNotice(
                    sessionUid = sessionUid,
                    sessionDate = sessionDate,
                    routineUid = routineUid,
                    routineName = "Daily Standup",
                    memberId = "U_TARGET",
                    commandBasicInfo = basicInfo,
                )

                then("the DM is targeted at the member's user ID (Slack treats user_id as DM channel)") {
                    capturedTargetUser.captured shouldBe "U_TARGET"
                }

                then("routingExtras carry sessionUid then routineUid for StandupFillContext routing") {
                    capturedExtras.captured shouldBe listOf(sessionUid.toString(), routineUid.toString())
                }

                then("ApprovalContents uses sessionUid as the idempotency key and STANDUP_FILL type") {
                    capturedContents.captured.idempotencyKey shouldBe sessionUid
                    capturedContents.captured.commandDetailType shouldBe CommandDetailType.STANDUP_FILL
                }

                then("the headline includes the routine name and the session date") {
                    capturedContents.captured.headLineText shouldBe "Daily Standup — 2026-05-04"
                }

                then("buttons read 'Fill in standup' and 'Skip'") {
                    capturedContents.captured.approvalButtonName shouldBe "Fill in standup"
                    capturedContents.captured.rejectButtonName shouldBe "Skip"
                }

                then("the call is delegated through SlackApiEventConstructor.simpleApplyRejectRequest") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApplyRejectRequest(
                            commandDetailType = CommandDetailType.STANDUP_FILL,
                            commandBasicInfo = basicInfo,
                            approvalContents = any(),
                            targetUserId = "U_TARGET",
                            routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
                        )
                    }
                }
            }
        }

        given("buildNudgeNotice") {
            `when`("called with a routine name, cutoff, and timezone") {
                val slackEventBuilder = mockk<SlackApiEventConstructor>()
                val builder = StandupDispatchMessageBuilder(slackEventBuilder = slackEventBuilder)
                val basicInfo = createCommandBasicInfo()
                // 2026-05-04T01:00:00Z = Asia/Seoul 10:00 — verifies the cutoff renders in the
                // routine's own zone, not UTC.
                val cutoffAt = Instant.parse("2026-05-04T01:00:00Z")

                val capturedBody = slot<String>()
                val capturedHeadline = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = capture(capturedHeadline),
                        commandBasicInfo = any(),
                        simpleString = capture(capturedBody),
                    )
                } returns
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.STANDUP_FILL,
                        idempotencyKey = basicInfo.idempotencyKey,
                    )

                builder.buildNudgeNotice(
                    routineName = "Daily Standup",
                    cutoffAt = cutoffAt,
                    routineTimezone = ZoneId.of("Asia/Seoul"),
                    commandBasicInfo = basicInfo,
                )

                then("the body names the routine and the cutoff time rendered in the routine zone") {
                    capturedBody.captured shouldContain "*Daily Standup*"
                    capturedBody.captured shouldContain "closes at 10:00"
                    capturedBody.captured shouldContain "haven't responded yet"
                    capturedBody.captured shouldContain "*Fill in standup*"
                }

                then("it delegates to a plain-text request typed STANDUP_FILL — no interactive buttons") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.STANDUP_FILL,
                            headLineText = any(),
                            commandBasicInfo = basicInfo,
                            simpleString = any(),
                        )
                    }
                }
            }
        }
    })
