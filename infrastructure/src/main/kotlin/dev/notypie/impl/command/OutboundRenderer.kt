package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.templates.SlackBlockLimits
import dev.notypie.templates.escapeMrkdwn
import dev.notypie.templates.splitSectionText
import dev.notypie.templates.truncateSectionText

private const val MAX_QUOTED_LINE_LENGTH: Int = SlackBlockLimits.SECTION_TEXT_BUDGET / 2

interface OutboundRenderer {
    fun render(message: OutboundMessage, basicInfo: CommandBasicInfo): SlackEventPayload
}

class SlackOutboundRenderer(
    private val slackEventBuilder: SlackApiEventConstructor,
) : OutboundRenderer {
    override fun render(message: OutboundMessage, basicInfo: CommandBasicInfo): SlackEventPayload =
        when (message) {
            is OutboundMessage.ChannelMessage ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline.orEmpty(),
                            commandBasicInfo = basicInfo,
                            simpleString = content.markdown,
                            threadTs = message.threadId,
                        )

                    is MessageContent.ErrorNotice ->
                        slackEventBuilder.detailErrorTextRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.ERROR_RESPONSE,
                            errorClassName = content.className,
                            errorMessage = content.message,
                            details = content.details,
                            commandBasicInfo = basicInfo,
                        )

                    is MessageContent.Schedule ->
                        slackEventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            timeScheduleInfo = content.info,
                        )

                    is MessageContent.MeetingRequest ->
                        slackEventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = message.detailType ?: CommandDetailType.MEETING_CREATE_REQUEST,
                            approvalContents = content.approval,
                        )

                    is MessageContent.StandupSummary ->
                        slackEventBuilder.standupSummaryRequest(
                            commandBasicInfo = basicInfo,
                            routineName = content.routineName,
                            sessionDate = content.sessionDate,
                            members = content.members,
                            answers = content.answers,
                            questions = content.questions,
                        )

                    is MessageContent.AgentQuestion ->
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.AGENT_ASK_QUESTION,
                            headLineText = "",
                            commandBasicInfo = basicInfo,
                            simpleString = content.askedBy(publisherId = basicInfo.publisherId),
                            threadTs = message.threadId,
                        )

                    is MessageContent.MeetingList -> error("not yet migrated: $content")
                }.payload

            is OutboundMessage.Ephemeral ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleEphemeralTextRequest(
                            headLineText = content.headline,
                            textMessage = content.markdown,
                            commandBasicInfo = basicInfo,
                            commandDetailType = message.detailType ?: CommandDetailType.SIMPLE_TEXT,
                            targetUserId = message.recipient?.id,
                        )

                    is MessageContent.MeetingList ->
                        slackEventBuilder.getMeetingListFormRequest(
                            myMeetings = content.meetings,
                            commandBasicInfo = basicInfo,
                            commandDetailType = message.detailType ?: CommandDetailType.GET_MEETING_LIST,
                            currentUserId = content.currentUserId,
                        )

                    is MessageContent.ErrorNotice,
                    is MessageContent.Schedule,
                    is MessageContent.MeetingRequest,
                    is MessageContent.StandupSummary,
                    is MessageContent.AgentQuestion,
                    -> error("Ephemeral content not yet migrated: $content")
                }.payload

            is OutboundMessage.Approval ->
                slackEventBuilder
                    .simpleApplyRejectRequest(
                        commandDetailType = message.approval.commandDetailType,
                        commandBasicInfo = basicInfo,
                        approvalContents = message.approval,
                        targetUserId = message.recipient?.id,
                        routingExtras =
                            listOf(message.approval.subTitle).filter { it.isNotBlank() } + message.routingExtras,
                    ).payload

            is OutboundMessage.Notice -> {
                val userMentions = message.mentions.joinToString(" ") { "<@${it.id}>" }
                slackEventBuilder
                    .simpleTextRequest(
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        headLineText = "Notice!",
                        commandBasicInfo = basicInfo,
                        simpleString = "[Notice] $userMentions ${message.message.escapeMrkdwn()}",
                    ).payload
            }

            is OutboundMessage.UpdateMessage ->
                slackEventBuilder
                    .updateNoticeMessageRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = message.detailType,
                        channel = message.ref.conversation.id,
                        messageTs = message.ref.messageId,
                        markdownText = message.content.markdown,
                    ).payload

            is OutboundMessage.ReplaceMessage ->
                slackEventBuilder
                    .replaceOriginalText(
                        markdownText = message.content.markdown,
                        responseUrl = message.handle.raw,
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.REPLACE_TEXT,
                    ).payload

            is OutboundMessage.OpenModal ->
                error("OpenModal is not a renderer concern; open views synchronously via the stager: $message")

            is OutboundMessage.DirectMessage ->
                error("DirectMessage is not a renderer concern: $message")
        }

    private fun MessageContent.AgentQuestion.askedBy(publisherId: String): String =
        prompt
            .escapeMrkdwn()
            .lines()
            .flatMap { line -> line.wrappedForQuote() }
            .joinToString(separator = "\n", prefix = "<@$publisherId> asked:\n") { line -> "> $line" }
            .truncateSectionText(limit = SlackBlockLimits.MESSAGE_BODY_BUDGET)

    private fun String.wrappedForQuote(): List<String> =
        splitSectionText(
            text = this,
            maxSections = Int.MAX_VALUE,
            balanceCodeFences = false,
            budget = MAX_QUOTED_LINE_LENGTH,
        ).flatMap { chunk -> chunk.lines() }
}
