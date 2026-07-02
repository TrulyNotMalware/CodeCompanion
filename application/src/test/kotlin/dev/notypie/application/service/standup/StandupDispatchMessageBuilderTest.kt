package dev.notypie.application.service.standup

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.impl.command.event.createSendSlackMessageEvent
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
                val stager = mockk<OutboundMessageStager>()
                val basicInfo = createCommandBasicInfo()
                val routineUid = UUID.randomUUID()
                val sessionUid = UUID.randomUUID()
                val sessionDate = LocalDate.of(2026, 5, 4)

                val capturedMessage = slot<OutboundMessage>()
                every {
                    stager.stage(message = capture(capturedMessage), basicInfo = any())
                } returns
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.STANDUP_PROMPT,
                        idempotencyKey = basicInfo.idempotencyKey,
                    )

                buildDmNotice(
                    stager = stager,
                    sessionUid = sessionUid,
                    sessionDate = sessionDate,
                    routineUid = routineUid,
                    routineName = "Daily Standup",
                    memberId = "U_TARGET",
                    commandBasicInfo = basicInfo,
                )

                val approvalMessage = capturedMessage.captured as OutboundMessage.Approval

                then("the DM is targeted at the member's user ID (Slack treats user_id as DM channel)") {
                    approvalMessage.recipient shouldBe UserRef(id = "U_TARGET")
                }

                then("routingExtras carry sessionUid then routineUid for StandupFillContext routing") {
                    approvalMessage.routingExtras shouldBe listOf(sessionUid.toString(), routineUid.toString())
                }

                then("ApprovalContents uses sessionUid as the idempotency key and STANDUP_PROMPT type") {
                    approvalMessage.approval.idempotencyKey shouldBe sessionUid
                    approvalMessage.approval.commandDetailType shouldBe CommandDetailType.STANDUP_PROMPT
                }

                then("the headline includes the routine name and the session date") {
                    approvalMessage.approval.headLineText shouldBe "Daily Standup — 2026-05-04"
                }

                then("buttons read 'Fill in standup' and 'Skip'") {
                    approvalMessage.approval.approvalButtonName shouldBe "Fill in standup"
                    approvalMessage.approval.rejectButtonName shouldBe "Skip"
                }

                then("the notice is staged as an Approval to the command channel with the given basicInfo") {
                    approvalMessage.target shouldBe ConversationTarget(id = basicInfo.channel)
                    verify(exactly = 1) { stager.stage(message = any(), basicInfo = basicInfo) }
                }
            }
        }

        given("buildNudgeNotice") {
            `when`("called with a routine name, cutoff, and timezone") {
                val stager = mockk<OutboundMessageStager>()
                val basicInfo = createCommandBasicInfo()
                // 2026-05-04T01:00:00Z = Asia/Seoul 10:00 — verifies the cutoff renders in the
                // routine's own zone, not UTC.
                val cutoffAt = Instant.parse("2026-05-04T01:00:00Z")

                val capturedMessage = slot<OutboundMessage>()
                every {
                    stager.stage(message = capture(capturedMessage), basicInfo = any())
                } returns
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.STANDUP_PROMPT,
                        idempotencyKey = basicInfo.idempotencyKey,
                    )

                buildNudgeNotice(
                    stager = stager,
                    routineName = "Daily Standup",
                    cutoffAt = cutoffAt,
                    routineTimezone = ZoneId.of("Asia/Seoul"),
                    commandBasicInfo = basicInfo,
                )

                val channelMessage = capturedMessage.captured as OutboundMessage.ChannelMessage
                val text = channelMessage.content as MessageContent.Text

                then("the body names the routine and the cutoff time rendered in the routine zone") {
                    text.markdown shouldContain "*Daily Standup*"
                    text.markdown shouldContain "closes at 10:00"
                    text.markdown shouldContain "haven't responded yet"
                    text.markdown shouldContain "*Fill in standup*"
                }

                then("it stages a plain-text ChannelMessage typed STANDUP_PROMPT — no interactive buttons") {
                    channelMessage.detailType shouldBe CommandDetailType.STANDUP_PROMPT
                    text.headline shouldBe "Standup reminder"
                    verify(exactly = 1) { stager.stage(message = any(), basicInfo = basicInfo) }
                }
            }
        }
    })
