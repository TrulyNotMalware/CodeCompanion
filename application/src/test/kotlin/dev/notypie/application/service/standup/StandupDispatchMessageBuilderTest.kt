package dev.notypie.application.service.standup

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.SlackApiEventConstructor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
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
    })
