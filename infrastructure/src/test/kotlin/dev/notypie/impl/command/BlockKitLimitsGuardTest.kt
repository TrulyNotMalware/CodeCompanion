package dev.notypie.impl.command

import dev.notypie.common.jsonMapper
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.command.outbound.TopicOption
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.meet.createMeetingParticipantDto
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createStandupAnswerDto
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.impl.command.event.ActionEventPayloadContents
import dev.notypie.impl.command.event.PostEventPayloadContents
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackBlockLimits
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Slack rejects a whole message or view that crosses any Block Kit limit, and the relay treats that as permanent.
// T3 (AI answer), T4 (standup summary) and V6 (topic names) all came from data that grows without a test ever
// checking the rendered payload, so this spec renders the real pipeline with worst-case inputs and walks the JSON.
private const val MESSAGE_MAX_BLOCKS = SlackBlockLimits.MESSAGE_MAX_BLOCKS
private const val VIEW_MAX_BLOCKS = 100

class BlockKitLimitsGuardTest :
    BehaviorSpec({
        // The approval template looks the publisher up; a failed lookup renders the `<@id>` fallback.
        val restRequester =
            mockk<RestRequester>().also { requester ->
                every {
                    requester.safeGet(
                        uri = any(),
                        authorizationHeader = any(),
                        responseType = SlackUserProfileDto::class.java,
                        uriVariables = any(),
                    )
                } returns Result.failure(RestClientException("users.profile.get unavailable"))
            }
        val templateBuilder = ModalTemplateBuilder(restRequester = restRequester, slackApiToken = TEST_BOT_TOKEN)
        val eventConstructor = SlackApiEventConstructor(botToken = TEST_BOT_TOKEN, templateBuilder = templateBuilder)
        val renderer = SlackOutboundRenderer(slackEventBuilder = eventConstructor)
        val basicInfo = createCommandBasicInfo()
        val target = ConversationTarget(id = TEST_CHANNEL_ID)

        // ~250K characters with code fences, links and long unbroken runs — the sidecar allows 256K.
        val longAiAnswer =
            buildString {
                repeat(times = 1_500) { index ->
                    append("Step $index: see <https://example.com/$index|docs> and run `make $index`\n")
                    if (index % 50 == 0) append("```\n").append("x".repeat(n = 4_000)).append("\n```\n")
                }
            }
        val standupQuestions = (1..8).map { index -> "Q$index " + "q".repeat(n = 196) }
        val standupAnswerCap =
            jsonMapper
                .readTree(
                    templateBuilder.standupModalViewJson(
                        routineName = "Daily",
                        sessionDate = LocalDate.of(2026, 5, 4),
                        sessionUid = UUID.randomUUID(),
                        userId = TEST_USER_ID,
                        noticeChannel = "D_NOTICE",
                        noticeMessageTs = "1700000000.000400",
                        questions = standupQuestions,
                    ),
                ).path("blocks")
                .first { it.path("type").asString() == "input" }
                .path("element")
                .path("max_length")
                .asInt()
        val standupMembers = (1..30).map { createRoutineMemberDto(userId = "U0123456789$it") }

        given("messages rendered from data that can grow without bound") {
            val cases =
                listOf(
                    "a 250K-character AI answer in a thread" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content =
                                MessageContent.Text(
                                    headline = "CodeCompanion — AI assistant",
                                    markdown = longAiAnswer,
                                ),
                            detailType = CommandDetailType.AGENT_CONVERSE,
                            threadId = TEST_THREAD_TS,
                        ),
                    "a long ephemeral reply" to
                        OutboundMessage.Ephemeral(
                            target = target,
                            recipient = UserRef(id = TEST_USER_ID),
                            content = MessageContent.Text(headline = null, markdown = longAiAnswer),
                        ),
                    "a 30-member standup summary answering eight long questions at the modal cap" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content =
                                MessageContent.StandupSummary(
                                    routineName = "R".repeat(n = 59),
                                    sessionDate = LocalDate.of(2026, 5, 4),
                                    members = standupMembers,
                                    answers =
                                        standupMembers.map { member ->
                                            createStandupAnswerDto(
                                                userId = member.userId,
                                                responses =
                                                    standupQuestions.map {
                                                        "a&<>".repeat(
                                                            n =
                                                                standupAnswerCap / 4,
                                                        )
                                                    },
                                            )
                                        },
                                    questions = standupQuestions,
                                ),
                        ),
                    "an error notice with a huge stack trace" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content =
                                MessageContent.ErrorNotice(
                                    className = "IllegalStateException",
                                    message = "boom",
                                    details = "at frame\n".repeat(n = 40_000),
                                ),
                        ),
                    // H11: the message lands in a section field (2,000) and used to be checked only as "boom".
                    "an error notice whose exception message is longer than a section field" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content =
                                MessageContent.ErrorNotice(
                                    className = "DataIntegrityViolationException",
                                    message = "could not execute statement ".repeat(n = 200),
                                    details = null,
                                ),
                        ),
                    "a channel reply whose text carries no headline" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content = MessageContent.Text(headline = null, markdown = longAiAnswer),
                        ),
                    "a meeting approval request with a control-character subtitle" to
                        OutboundMessage.Approval(
                            target = target,
                            recipient = UserRef(id = TEST_USER_ID),
                            approval =
                                ApprovalContents(
                                    reason = "r".repeat(n = 2_000),
                                    subTitle = "<!channel>&<>".repeat(n = 2),
                                    publisherId = TEST_USER_ID,
                                    idempotencyKey = UUID.randomUUID(),
                                    commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                                ),
                        ),
                    "a notice carrying a long message and many mentions" to
                        OutboundMessage.Notice(
                            target = target,
                            mentions = (1..50).map { UserRef(id = "U0123456789$it") },
                            message = "<!here> & <https://evil.example|x> ".repeat(n = 1_500),
                        ),
                    "a schedule notice" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content =
                                MessageContent.Schedule(
                                    headline = "Meeting scheduled — " + "t".repeat(n = 20),
                                    info =
                                        TimeScheduleInfo(
                                            scheduleName = "<&>".repeat(n = 7),
                                            startTime = LocalDateTime.of(2026, 5, 4, 10, 0),
                                            endTime = LocalDateTime.of(2026, 5, 4, 11, 0),
                                        ),
                                ),
                        ),
                    "a host's meeting list longer than the page" to
                        OutboundMessage.Ephemeral(
                            target = target,
                            content =
                                MessageContent.MeetingList(
                                    meetings =
                                        (1..40).map {
                                            createMeetingDto(
                                                creator = TEST_USER_ID,
                                                participants =
                                                    (1..10).map { index ->
                                                        createMeetingParticipantDto(
                                                            userId = "U_P$index",
                                                            isAttending = false,
                                                            absentReason = RejectReason.OTHER,
                                                            absentReasonDetail = "d".repeat(n = 255),
                                                        )
                                                    },
                                            )
                                        },
                                    currentUserId = TEST_USER_ID,
                                ),
                        ),
                    "a long in-place update of a posted message" to
                        OutboundMessage.UpdateMessage(
                            ref = MessageRef(conversation = target, messageId = "1700000000.000300"),
                            content = MessageContent.Text(headline = null, markdown = longAiAnswer),
                            detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                        ),
                    "a long response_url replacement" to
                        OutboundMessage.ReplaceMessage(
                            handle = ResponseReplaceHandle(raw = "https://hooks.slack.com/actions/T/1/x"),
                            content = MessageContent.Text(headline = null, markdown = longAiAnswer),
                        ),
                )

            cases.forEach { (name, message) ->
                `when`(name) {
                    val blocks = renderedBlocks(payload = renderer.render(message = message, basicInfo = basicInfo))

                    then("the rendered blocks stay within Slack's Block Kit limits") {
                        assertWithinBlockKitLimits(blocks = blocks, maxBlocks = MESSAGE_MAX_BLOCKS)
                    }
                }
            }
        }

        given("modals rendered from configuration or user data") {
            val cases =
                listOf(
                    "a subscribe modal offering 100 topics with 120-character display names" to
                        templateBuilder.cveSubscribeModalViewJson(
                            idempotencyKey = UUID.randomUUID(),
                            topics =
                                (1..SlackBlockLimits.MAX_OPTIONS).map { index ->
                                    TopicOption(key = "topic-$index", label = "Topic $index " + "n".repeat(n = 110))
                                },
                        ),
                    "a standup modal with eight 199-character questions" to
                        templateBuilder.standupModalViewJson(
                            routineName = "R".repeat(n = 59),
                            sessionDate = LocalDate.of(2026, 5, 4),
                            sessionUid = UUID.randomUUID(),
                            userId = TEST_USER_ID,
                            noticeChannel = "D_NOTICE",
                            noticeMessageTs = "1700000000.000400",
                            questions = standupQuestions,
                        ),
                    "a decline-reason modal" to
                        templateBuilder.declineReasonModalViewJson(
                            meetingTitle = "t".repeat(n = 20),
                            meetingIdempotencyKey = UUID.randomUUID(),
                            participantUserId = TEST_USER_ID,
                            noticeChannel = TEST_CHANNEL_ID,
                            noticeMessageTs = "1700000000.000100",
                        ),
                )

            cases.forEach { (name, viewJson) ->
                `when`(name) {
                    val blocks = jsonMapper.readTree(viewJson).path("blocks")

                    then("the view stays within Slack's Block Kit limits") {
                        assertWithinBlockKitLimits(blocks = blocks, maxBlocks = VIEW_MAX_BLOCKS)
                    }
                }
            }
        }
    })

private fun renderedBlocks(payload: SlackEventPayload): JsonNode =
    when (payload) {
        is PostEventPayloadContents -> jsonMapper.readTree(payload.body.getValue("blocks") as String)
        is ActionEventPayloadContents -> jsonMapper.readTree(payload.body).path("blocks")
        else -> error("unexpected payload type: ${payload::class.simpleName}")
    }

private fun assertWithinBlockKitLimits(blocks: JsonNode, maxBlocks: Int) {
    blocks.isArray shouldBe true
    blocks.size() shouldBeGreaterThan 0
    blocks.size() shouldBeLessThanOrEqual maxBlocks
    blocks.forEach { block -> assertNodeWithinLimits(node = block) }
}

private fun assertNodeWithinLimits(node: JsonNode) {
    if (node.isObject) {
        when (node.path("type").asString()) {
            "section" -> {
                val text = node.path("text")
                if (text.isObject) {
                    val value = text.path("text").asString()
                    value.isNotBlank() shouldBe true
                    value.length shouldBeLessThanOrEqual SlackBlockLimits.SECTION_TEXT_MAX_LENGTH
                }
                node.path("fields").forEach { field ->
                    field.path("text").asString().length shouldBeLessThanOrEqual
                        SlackBlockLimits.SECTION_FIELD_MAX_LENGTH
                }
            }
            // Slack rejects an empty header as well as a long one (H11: a null headline rendered as "").
            "header" -> {
                val value = node.path("text").path("text").asString()
                value.isNotBlank() shouldBe true
                value.length shouldBeLessThanOrEqual SlackBlockLimits.HEADER_TEXT_MAX_LENGTH
            }
            "plain_text_input" ->
                if (node.has("max_length")) {
                    node.path("max_length").asInt() shouldBeLessThanOrEqual SlackBlockLimits.PLAIN_TEXT_INPUT_MAX_LENGTH
                }
        }
        val options = node.path("options")
        if (options.isArray) {
            options.size() shouldBeLessThanOrEqual SlackBlockLimits.MAX_OPTIONS
            options.forEach { option ->
                option
                    .path("text")
                    .path("text")
                    .asString()
                    .length shouldBeLessThanOrEqual
                    SlackBlockLimits.OPTION_TEXT_MAX_LENGTH
            }
        }
    }
    node.forEach { child -> assertNodeWithinLimits(node = child) }
}
