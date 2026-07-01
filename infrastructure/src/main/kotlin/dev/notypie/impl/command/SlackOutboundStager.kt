package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager

/**
 * Slack adapter that renders the text and approval/form families of [OutboundMessage]s into staged
 * [CommandEvent]s, reproducing exactly the builder calls the [SlackIntentResolver] made for the
 * now-removed `TextResponse`/`EphemeralResponse`/`ErrorDetail`/`TimeSchedule`/`Notice` intents and the
 * `ApplyReject`/`ApprovalForm`/`MeetingForm` intents. Variants not yet migrated fail loudly.
 */
class SlackOutboundStager(
    private val slackEventBuilder: SlackApiEventConstructor,
) : OutboundMessageStager {
    override fun stage(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (message) {
            is OutboundMessage.ChannelMessage ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline.orEmpty(),
                            commandBasicInfo = basicInfo,
                            simpleString = content.markdown,
                        )

                    is MessageContent.ErrorNotice ->
                        slackEventBuilder.detailErrorTextRequest(
                            commandDetailType = CommandDetailType.ERROR_RESPONSE,
                            errorClassName = content.className,
                            errorMessage = content.message,
                            details = content.details,
                            commandBasicInfo = basicInfo,
                        )

                    is MessageContent.Schedule ->
                        slackEventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            timeScheduleInfo = content.info,
                        )

                    is MessageContent.Form ->
                        slackEventBuilder.simpleApprovalFormRequest(
                            commandDetailType = CommandDetailType.APPROVAL_FORM,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            selectionFields = content.fields,
                            reasonInput = content.reason,
                            approvalContents = content.approval,
                        )

                    is MessageContent.MeetingRequest ->
                        slackEventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                            approvalContents = content.approval,
                        )

                    else -> error("not yet migrated: $content")
                }

            is OutboundMessage.Ephemeral -> {
                val content = message.content
                check(content is MessageContent.Text) { "Ephemeral content must be Text: $content" }
                slackEventBuilder.simpleEphemeralTextRequest(
                    textMessage = content.markdown,
                    commandBasicInfo = basicInfo,
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    targetUserId = message.recipient?.id,
                )
            }

            is OutboundMessage.Approval ->
                slackEventBuilder.simpleApplyRejectRequest(
                    commandDetailType = message.approval.commandDetailType,
                    commandBasicInfo = basicInfo,
                    approvalContents = message.approval,
                    targetUserId = message.recipient?.id,
                    // Propagate the human-readable subtitle (meeting title for notice DMs) through the
                    // routing text so context handlers can surface it without a separate DB lookup.
                    // Blank subtitles are filtered out to keep the routing token stable for flows
                    // that don't use subTitle.
                    routingExtras =
                        listOf(message.approval.subTitle).filter { it.isNotBlank() } + message.routingExtras,
                )

            is OutboundMessage.Notice -> {
                val userMentions = message.mentions.joinToString(" ") { "<@${it.id}>" }
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    headLineText = "Notice!",
                    commandBasicInfo = basicInfo,
                    simpleString = "[Notice] $userMentions ${message.message}",
                )
            }

            is OutboundMessage.UpdateMessage -> {
                val content = message.content
                check(content is MessageContent.Text) { "UpdateMessage content must be Text: $content" }
                slackEventBuilder.updateNoticeMessageRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = message.detailType,
                    channel = message.ref.conversation.id,
                    messageTs = message.ref.messageId,
                    markdownText = content.markdown,
                )
            }

            is OutboundMessage.ReplaceMessage -> {
                val content = message.content
                check(content is MessageContent.Text) { "ReplaceMessage content must be Text: $content" }
                slackEventBuilder.replaceOriginalText(
                    markdownText = content.markdown,
                    responseUrl = message.handle.raw,
                    commandBasicInfo = basicInfo,
                    commandDetailType = CommandDetailType.REPLACE_TEXT,
                )
            }

            else -> error("OutboundMessage variant not yet migrated to stager: $message")
        }
}
