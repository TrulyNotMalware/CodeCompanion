package dev.notypie.application.service.standup

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.UserRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class StandupDispatchMessageBuilderTest :
    BehaviorSpec({
        given("buildDmNotice") {
            `when`("called with primitive routing fields") {
                val basicInfo = createCommandBasicInfo()
                val routineUid = UUID.randomUUID()
                val sessionUid = UUID.randomUUID()
                val sessionDate = LocalDate.of(2026, 5, 4)

                val approvalMessage =
                    buildDmNotice(
                        sessionUid = sessionUid,
                        sessionDate = sessionDate,
                        routineUid = routineUid,
                        routineName = "Daily Standup",
                        memberId = "U_TARGET",
                        commandBasicInfo = basicInfo,
                    )

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

                then("the notice is built as an Approval to the command channel") {
                    approvalMessage.target shouldBe ConversationTarget(id = basicInfo.channel)
                }
            }
        }

        given("buildNudgeNotice") {
            `when`("called with a routine name, cutoff, and timezone") {
                val basicInfo = createCommandBasicInfo()
                val cutoffAt = Instant.parse("2026-05-04T01:00:00Z")

                val channelMessage =
                    buildNudgeNotice(
                        routineName = "Daily Standup",
                        cutoffAt = cutoffAt,
                        routineTimezone = ZoneId.of("Asia/Seoul"),
                        commandBasicInfo = basicInfo,
                    )
                val text = channelMessage.content as MessageContent.Text

                then("the body names the routine and the cutoff time rendered in the routine zone") {
                    text.markdown shouldContain "*Daily Standup*"
                    text.markdown shouldContain "closes at 10:00"
                    text.markdown shouldContain "haven't responded yet"
                    text.markdown shouldContain "*Fill in standup*"
                }

                then("it builds a plain-text ChannelMessage typed STANDUP_PROMPT — no interactive buttons") {
                    channelMessage.detailType shouldBe CommandDetailType.STANDUP_PROMPT
                    text.headline shouldBe "Standup reminder"
                }
            }

            // G3: the nudge goes to every non-responder; an unescaped name rendered as a disguised link in each DM.
            `when`("the routine name carries a disguised link and an ampersand") {
                val text =
                    buildNudgeNotice(
                        routineName = "<https://evil.example|Fill in standup> & co",
                        cutoffAt = Instant.parse("2026-05-04T01:00:00Z"),
                        routineTimezone = ZoneId.of("Asia/Seoul"),
                        commandBasicInfo = createCommandBasicInfo(),
                    ).content as MessageContent.Text

                then("the name is escaped inside the bold, and the template's own *bold* stays markup") {
                    text.markdown shouldContain "*&lt;https://evil.example|Fill in standup&gt; &amp; co*"
                    text.markdown shouldNotContain "<https://evil.example"
                    text.markdown shouldContain "*Fill in standup* button"
                }
            }
        }
    })
