package dev.notypie.templates

import dev.notypie.common.jsonMapper
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createApprovalContents
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.SlackOutboundRenderer
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.impl.command.event.ActionEventPayloadContents
import dev.notypie.impl.command.event.PostEventPayloadContents
import dev.notypie.impl.command.event.SlackEventPayload
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

private const val VIEW_MAX_BLOCKS = 100
private const val AI_ANSWER_MAX_LENGTH = 40_000
private const val AI_ANSWER_MAX_MESSAGES = 8
private const val SLASH_COMMAND_TEXT_MAX_LENGTH = 4_000

class BlockKitLimitsGuardTest :
    BehaviorSpec({
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
        val renderer =
            SlackOutboundRenderer(
                slackEventBuilder =
                    SlackApiEventConstructor(
                        botToken = TEST_BOT_TOKEN,
                        templateBuilder = templateBuilder,
                    ),
            )
        val basicInfo = createCommandBasicInfo()
        val target = ConversationTarget(id = TEST_CHANNEL_ID)

        val aiAnswer =
            buildString {
                var index = 0
                while (length < AI_ANSWER_MAX_LENGTH) {
                    append("Step $index: see <https://example.com/$index|docs> and run `make $index`\n")
                    if (index % 40 == 0) append("```\n").append("x".repeat(n = 2_000)).append("\n```\n")
                    index++
                }
            }.take(n = AI_ANSWER_MAX_LENGTH)
        val aiAnswerParts = splitMessageText(text = aiAnswer, maxMessages = AI_ANSWER_MAX_MESSAGES)
        val fullBody =
            "```\n" +
                List(size = SlackBlockLimits.MESSAGE_BODY_BUDGET / 1_448) { "c".repeat(n = 1_447) }
                    .joinToString(separator = "\n") + "\n```"
        val standupQuestions = (1..8).map { index -> "Q$index " + "q".repeat(n = 196) }

        given("messages rendered from data that can grow") {
            val cases =
                aiAnswerParts.mapIndexed { index, part ->
                    "part ${index + 1} of ${aiAnswerParts.size} of a 40,000-character AI answer" to
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content =
                                MessageContent.Text(
                                    headline = "CodeCompanion — AI assistant (${index + 1}/${aiAnswerParts.size})",
                                    markdown = part,
                                ),
                            detailType = CommandDetailType.AGENT_CONVERSE,
                            threadId = TEST_THREAD_TS,
                        )
                } +
                    listOf(
                        "an ephemeral reply filling the message body budget" to
                            OutboundMessage.Ephemeral(
                                target = target,
                                recipient = UserRef(id = TEST_USER_ID),
                                content = MessageContent.Text(headline = null, markdown = fullBody),
                            ),
                        "a channel reply without a headline" to
                            OutboundMessage.ChannelMessage(
                                target = target,
                                content = MessageContent.Text(headline = null, markdown = fullBody),
                            ),
                        "an in-place update filling the message body budget" to
                            OutboundMessage.UpdateMessage(
                                ref = MessageRef(conversation = target, messageId = "1700000000.000300"),
                                content = MessageContent.Text(headline = null, markdown = fullBody),
                                detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                            ),
                        "a response_url replacement filling the message body budget" to
                            OutboundMessage.ReplaceMessage(
                                handle = ResponseReplaceHandle(raw = "https://hooks.slack.com/actions/T/1/x"),
                                content = MessageContent.Text(headline = null, markdown = fullBody),
                            ),
                        "a notice with a slash-command-length message and many mentions" to
                            OutboundMessage.Notice(
                                target = target,
                                mentions = (1..50).map { UserRef(id = "U0123456789$it") },
                                message = "n".repeat(n = SLASH_COMMAND_TEXT_MAX_LENGTH),
                            ),
                        "an approval request with markup in its subtitle" to
                            OutboundMessage.Approval(
                                target = target,
                                recipient = UserRef(id = TEST_USER_ID),
                                approval =
                                    createApprovalContents(
                                        commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                                    ).copy(subTitle = "<!channel>&<>".repeat(n = 2)),
                            ),
                        "a schedule notice" to
                            OutboundMessage.ChannelMessage(
                                target = target,
                                content =
                                    MessageContent.Schedule(
                                        headline = "Meeting scheduled",
                                        info =
                                            TimeScheduleInfo(
                                                scheduleName = "<&>".repeat(n = 7),
                                                startTime = LocalDateTime.of(2026, 5, 4, 10, 0),
                                                endTime = LocalDateTime.of(2026, 5, 4, 11, 0),
                                            ),
                                    ),
                            ),
                    )

            cases.forEach { (name, message) ->
                `when`(name) {
                    val blocks = renderedBlocks(payload = renderer.render(message = message, basicInfo = basicInfo))

                    then("the blocks stay within Block Kit limits and the message text budget") {
                        assertWithinBlockKitLimits(blocks = blocks, maxBlocks = SlackBlockLimits.MESSAGE_MAX_BLOCKS)
                        blockText(node = blocks) shouldBeLessThanOrEqual SlackBlockLimits.MESSAGE_TEXT_BUDGET
                    }
                }
            }
        }

        given("modals rendered from user data") {
            val cases =
                listOf(
                    "a standup modal with eight 200-character questions" to
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

                    then("the view stays within Block Kit limits") {
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

private fun blockText(node: JsonNode): Int {
    val own =
        if (node.isObject && node.path("type").asString() in setOf("plain_text", "mrkdwn")) {
            node.path("text").asString().length
        } else {
            0
        }
    return own + node.sumOf { child -> blockText(node = child) }
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
