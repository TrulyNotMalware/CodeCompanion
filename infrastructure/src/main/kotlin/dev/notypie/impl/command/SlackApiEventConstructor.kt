package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.app_backend.interactive_components.response.ActionResponse
import com.slack.api.methods.RequestFormBuilder
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.chat.ChatUpdateRequest
import com.slack.api.model.block.LayoutBlock
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.OpenViewEvent
import dev.notypie.domain.command.entity.event.OpenViewPayloadContents
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.SlackEventPayload
import dev.notypie.domain.command.entity.event.toMessageTypeByTargetUser
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks
import okhttp3.FormBody
import java.util.UUID

class SlackApiEventConstructor(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder,
) {
    private val slackConfig = Slack.getInstance().config

    fun simpleTextRequest(
        commandDetailType: CommandDetailType,
        headLineText: String,
        commandBasicInfo: CommandBasicInfo,
        simpleString: String,
    ): SendSlackMessageEvent {
        val layout =
            templateBuilder.simpleTextResponseTemplate(
                headLineText = headLineText,
                body = simpleString,
                isMarkDown = true,
            )
        return buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
        )
    }

    fun simpleEphemeralTextRequest(
        textMessage: String,
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        targetUserId: String? = null,
    ): SendSlackMessageEvent {
        val layout = templateBuilder.onlyTextTemplate(message = textMessage, isMarkDown = true)
        return buildEphemeralMessage(
            commandDetailType = commandDetailType,
            commandBasicInfo = commandBasicInfo,
            layout = layout,
            replaceOriginal = false,
            targetUserId = targetUserId,
        )
    }

    fun detailErrorTextRequest(
        commandDetailType: CommandDetailType,
        errorClassName: String,
        errorMessage: String,
        details: String?,
        commandBasicInfo: CommandBasicInfo,
    ): SendSlackMessageEvent {
        val errorHeaderText = "Error : $errorClassName"
        val layout =
            templateBuilder.errorNoticeTemplate(
                headLineText = errorHeaderText,
                errorMessage = errorMessage,
                details = details,
            )
        return buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
        )
    }

    fun simpleTimeScheduleRequest(
        commandDetailType: CommandDetailType,
        headLineText: String,
        commandBasicInfo: CommandBasicInfo,
        timeScheduleInfo: TimeScheduleInfo,
    ): SendSlackMessageEvent {
        val layout =
            templateBuilder.simpleScheduleNoticeTemplate(
                headLineText = headLineText,
                timeScheduleInfo = timeScheduleInfo,
            )
        return buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
        )
    }

    fun simpleApplyRejectRequest(
        commandDetailType: CommandDetailType,
        commandBasicInfo: CommandBasicInfo,
        approvalContents: ApprovalContents,
        targetUserId: String? = null,
        routingExtras: List<String> = emptyList(),
    ): SendSlackMessageEvent {
        val layout =
            templateBuilder.approvalTemplate(
                headLineText = approvalContents.headLineText,
                approvalContents = approvalContents,
                idempotencyKey = commandBasicInfo.idempotencyKey,
                commandDetailType = commandDetailType,
            )
        return buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
            targetUserId = targetUserId,
            routingExtras = routingExtras,
        )
    }

    fun simpleApprovalFormRequest(
        commandDetailType: CommandDetailType,
        headLineText: String,
        commandBasicInfo: CommandBasicInfo,
        selectionFields: List<SelectionContents>,
        reasonInput: TextInputContents? = null,
        approvalContents: ApprovalContents? = null,
    ): SendSlackMessageEvent {
        val layout =
            templateBuilder.requestApprovalFormTemplate(
                headLineText = headLineText,
                selectionFields = selectionFields,
                reasonInput = reasonInput,
                approvalContents =
                    approvalContents
                        ?: ApprovalContents(
                            reason = "Request Approval",
                            approvalButtonName = "Send",
                            rejectButtonName = "Cancel",
                            idempotencyKey = commandBasicInfo.idempotencyKey,
                            commandDetailType = commandDetailType,
                            publisherId = commandBasicInfo.publisherId,
                        ),
            )

        return buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
        )
    }

    fun requestMeetingFormRequest(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        approvalContents: ApprovalContents? = null,
    ): SendSlackMessageEvent {
        val layout =
            templateBuilder.requestMeetingFormTemplate(
                approvalContents =
                    approvalContents ?: ApprovalContents(
                        idempotencyKey = commandBasicInfo.idempotencyKey,
                        commandDetailType = commandDetailType,
                        reason = "Request Meeting",
                        publisherId = commandBasicInfo.publisherId,
                    ),
            )
        return buildEphemeralMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
        )
    }

    fun getMeetingListFormRequest(
        myMeetings: List<MeetingDto>,
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
    ): SendSlackMessageEvent {
        val layout = templateBuilder.meetingListFormTemplate(meetings = myMeetings)
        return buildEphemeralMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = false,
        )
    }

    fun openDeclineReasonModalRequest(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        triggerId: String,
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        meetingTitle: String,
        noticeChannel: String,
        noticeMessageTs: String,
    ): OpenViewEvent {
        val viewJson =
            templateBuilder.declineReasonModalViewJson(
                meetingTitle = meetingTitle,
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
                noticeChannel = noticeChannel,
                noticeMessageTs = noticeMessageTs,
            )
        val payload =
            OpenViewPayloadContents(
                eventId = UUID.randomUUID(),
                apiAppId = commandBasicInfo.appId,
                commandDetailType = commandDetailType,
                idempotencyKey = commandBasicInfo.idempotencyKey,
                publisherId = commandBasicInfo.publisherId,
                channel = commandBasicInfo.channel,
                triggerId = triggerId,
                viewJson = viewJson,
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
            )
        return OpenViewEvent(
            idempotencyKey = commandBasicInfo.idempotencyKey,
            payload = payload,
            type = commandDetailType,
        )
    }

    /**
     * Builds a `chat.update` request that rewrites the notice DM with a plain markdown body.
     * Routed through the outbox like any other [PostEventPayloadContents] — not latency-bound
     * (no trigger_id involved). The dispatcher branches on [MessageType.UPDATE_MESSAGE] to
     * call `chat.update` instead of `chat.postMessage`.
     */
    fun updateNoticeMessageRequest(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        channel: String,
        messageTs: String,
        markdownText: String,
    ): SendSlackMessageEvent {
        val layout = templateBuilder.onlyTextTemplate(message = markdownText, isMarkDown = true)
        val body =
            extractBodyData(
                chatUpdateRequest =
                    chatUpdateBuilder(
                        channel = channel,
                        ts = messageTs,
                        blocks = layout.template,
                        fallbackText = markdownText,
                    ),
            )
        val payload =
            PostEventPayloadContents(
                eventId = UUID.randomUUID(),
                apiAppId = commandBasicInfo.appId,
                messageType = MessageType.UPDATE_MESSAGE,
                commandDetailType = commandDetailType,
                idempotencyKey = commandBasicInfo.idempotencyKey,
                publisherId = commandBasicInfo.publisherId,
                channel = channel,
                replaceOriginal = false,
                body = body,
            )
        return payload.toSlackMessageEvent(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
        )
    }

    fun replaceOriginalText(
        markdownText: String,
        responseUrl: String,
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
    ): SendSlackMessageEvent {
        val layout = templateBuilder.onlyTextTemplate(message = markdownText, isMarkDown = true)
        return buildActionResponse(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
            layout = layout,
            replaceOriginal = true,
            responseUrl = responseUrl,
        )
    }

    private fun buildMessage(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        layout: LayoutBlocks,
        replaceOriginal: Boolean,
        targetUserId: String? = null,
        routingExtras: List<String> = emptyList(),
    ): SendSlackMessageEvent {
        val messageType = toMessageTypeByTargetUser(targetUserId = targetUserId)
        val payload =
            toEventContents(
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
                replaceOriginal = replaceOriginal,
                body =
                    extractBodyData(
                        chatPostMessageRequest =
                            chatPostMessageBuilder(
                                channel = commandBasicInfo.channel,
                                blocks = layout.template,
                                idempotencyKey = commandBasicInfo.idempotencyKey,
                                commandDetailType = commandDetailType,
                                targetUserId = targetUserId,
                                routingExtras = routingExtras,
                            ),
                    ),
                messageType = messageType,
            )
        return payload.toSlackMessageEvent(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
        )
    }

    private fun buildEphemeralMessage(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        layout: LayoutBlocks,
        replaceOriginal: Boolean,
        targetUserId: String? = null,
    ): SendSlackMessageEvent {
        val payload =
            toEventContents(
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
                replaceOriginal = replaceOriginal,
                body =
                    extractBodyData(
                        chatPostEphemeralRequest =
                            chatPostEphemeralBuilder(
                                channel =
                                    targetUserId ?: commandBasicInfo.channel,
                                blocks = layout.template,
                                idempotencyKey = commandBasicInfo.idempotencyKey,
                                commandDetailType = commandDetailType,
                                userId = targetUserId ?: commandBasicInfo.publisherId,
                            ),
                    ),
                messageType = MessageType.EPHEMERAL_MESSAGE,
            )
        return payload.toSlackMessageEvent(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
        )
    }

    private fun buildActionResponse(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        layout: LayoutBlocks,
        replaceOriginal: Boolean,
        responseUrl: String,
    ): SendSlackMessageEvent {
        val payload =
            toEventContents(
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
                body =
                    toSnakeCaseJsonString(
                        ActionResponse
                            .builder()
                            .blocks(layout.template)
                            .replaceOriginal(replaceOriginal)
                            .build(),
                    ),
                responseUrl = responseUrl,
            )
        return payload.toSlackMessageEvent(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType,
        )
    }

    private fun SlackEventPayload.toSlackMessageEvent(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
    ) = SendSlackMessageEvent(
        idempotencyKey = commandBasicInfo.idempotencyKey,
        payload = this,
        destination = "",
        timestamp = System.currentTimeMillis(),
        type = commandDetailType,
    )

    private fun extractBodyData(chatPostEphemeralRequest: ChatPostEphemeralRequest) =
        toMap(formBody = RequestFormBuilder.toForm(chatPostEphemeralRequest).build())

    private fun extractBodyData(chatPostMessageRequest: ChatPostMessageRequest) =
        toMap(formBody = RequestFormBuilder.toForm(chatPostMessageRequest).build())

    private fun extractBodyData(chatUpdateRequest: ChatUpdateRequest) =
        toMap(formBody = RequestFormBuilder.toForm(chatUpdateRequest).build())

    private fun toSnakeCaseJsonString(actionResponse: ActionResponse) =
        GsonFactory.createSnakeCase(slackConfig).toJson(actionResponse)

    private fun toEventContents(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        body: Map<String, String>,
        replaceOriginal: Boolean,
        messageType: MessageType,
    ) = PostEventPayloadContents(
        apiAppId = commandBasicInfo.appId,
        commandDetailType = commandDetailType,
        body = body,
        idempotencyKey = commandBasicInfo.idempotencyKey,
        publisherId = commandBasicInfo.publisherId,
        replaceOriginal = replaceOriginal,
        channel = commandBasicInfo.channel,
        eventId = UUID.randomUUID(),
        messageType = messageType,
    )

    private fun toEventContents(
        commandBasicInfo: CommandBasicInfo,
        commandDetailType: CommandDetailType,
        responseUrl: String,
        body: String,
    ) = ActionEventPayloadContents(
        apiAppId = commandBasicInfo.appId,
        commandDetailType = commandDetailType,
        idempotencyKey = commandBasicInfo.idempotencyKey,
        publisherId = commandBasicInfo.publisherId,
        channel = commandBasicInfo.channel,
        responseUrl = responseUrl,
        body = body,
        eventId = UUID.randomUUID(),
    )

    private fun toMap(formBody: FormBody): Map<String, String> =
        (0 until formBody.size).associate {
            formBody.name(it) to formBody.value(it)
        }

    // https://api.slack.com/methods/chat.postMessage
    private fun chatPostMessageBuilder(
        commandDetailType: CommandDetailType,
        idempotencyKey: UUID,
        channel: String,
        blocks: List<LayoutBlock>,
        targetUserId: String? = null,
        routingExtras: List<String> = emptyList(),
    ) = ChatPostMessageRequest
        .builder()
        .channel(targetUserId ?: channel)
        .text(buildRoutingText(idempotencyKey, commandDetailType, routingExtras))
        .token(botToken)
        .blocks(blocks)
        .build()

    /**
     * Builds the comma-separated routing text embedded in `message.text`. The parser mirrors
     * this format by splitting on `,` — every extra must therefore be URL-encoded so that
     * values with commas (meeting titles typed by users) don't collide with delimiters.
     */
    private fun buildRoutingText(
        idempotencyKey: UUID,
        commandDetailType: CommandDetailType,
        routingExtras: List<String>,
    ): String {
        val prefix = "$idempotencyKey,$commandDetailType"
        if (routingExtras.isEmpty()) return prefix
        val encoded =
            routingExtras.joinToString(",") {
                java.net.URLEncoder.encode(it, java.nio.charset.StandardCharsets.UTF_8)
            }
        return "$prefix,$encoded"
    }

    // FIXME Ephemeral message cannot include any texts with blocks field
    private fun chatPostEphemeralBuilder(
        commandDetailType: CommandDetailType,
        idempotencyKey: UUID,
        channel: String,
        blocks: List<LayoutBlock>,
        userId: String,
    ) = ChatPostEphemeralRequest
        .builder()
        .channel(channel)
        .text("$idempotencyKey, $commandDetailType")
        .token(botToken)
        .blocks(blocks)
        .user(userId)
        .build()

    // https://api.slack.com/methods/chat.update — requires the original message's channel + ts.
    private fun chatUpdateBuilder(
        channel: String,
        ts: String,
        blocks: List<LayoutBlock>,
        fallbackText: String,
    ) = ChatUpdateRequest
        .builder()
        .channel(channel)
        .ts(ts)
        .text(fallbackText)
        .token(botToken)
        .blocks(blocks)
        .build()
}
