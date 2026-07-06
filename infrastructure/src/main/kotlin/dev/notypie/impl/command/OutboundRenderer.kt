package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.event.SlackEventPayload

/**
 * Renders the message family of [OutboundMessage]s into a transport payload at deliver time,
 * independent of the staged-event envelope. Modal and direct-message families are not renderer
 * concerns and fail loudly.
 */
interface OutboundRenderer {
    fun render(message: OutboundMessage, basicInfo: CommandBasicInfo): SlackEventPayload
}

/**
 * Slack implementation that reuses [SlackApiEventConstructor] to build a message event, then
 * returns only its payload. It shares the constructor with [SlackOutboundStager] so both produce
 * byte-identical wire output; the stager keeps the staged-event envelope while the renderer drops it.
 */
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

                    is MessageContent.Form ->
                        slackEventBuilder.simpleApprovalFormRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.APPROVAL_REQUEST,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            selectionFields = content.fields,
                            reasonInput = content.reason,
                            approvalContents = content.approval,
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

                    else -> error("not yet migrated: $content")
                }.payload

            is OutboundMessage.Ephemeral ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleEphemeralTextRequest(
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

                    else -> error("Ephemeral content not yet migrated: $content")
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
                        simpleString = "[Notice] $userMentions ${message.message}",
                    ).payload
            }

            is OutboundMessage.UpdateMessage -> {
                val content = message.content
                check(content is MessageContent.Text) { "UpdateMessage content must be Text: $content" }
                slackEventBuilder
                    .updateNoticeMessageRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = message.detailType,
                        channel = message.ref.conversation.id,
                        messageTs = message.ref.messageId,
                        markdownText = content.markdown,
                    ).payload
            }

            is OutboundMessage.ReplaceMessage -> {
                val content = message.content
                check(content is MessageContent.Text) { "ReplaceMessage content must be Text: $content" }
                slackEventBuilder
                    .replaceOriginalText(
                        markdownText = content.markdown,
                        responseUrl = message.handle.raw,
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.REPLACE_TEXT,
                    ).payload
            }

            is OutboundMessage.OpenModal ->
                error("OpenModal is not a renderer concern; open views synchronously via the stager: $message")

            is OutboundMessage.DirectMessage ->
                error("DirectMessage is not a renderer concern: $message")
        }
}
