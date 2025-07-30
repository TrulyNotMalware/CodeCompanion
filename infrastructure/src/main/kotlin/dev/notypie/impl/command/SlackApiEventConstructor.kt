package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.app_backend.interactive_components.response.ActionResponse
import com.slack.api.methods.RequestFormBuilder
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.LayoutBlock
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.common.event.ActionEventPayloadContents
import dev.notypie.domain.common.event.MessageType
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.SendSlackMessageEvent
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks
import okhttp3.FormBody
import java.util.UUID

class SlackApiEventConstructor(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder,
    private val messageDispatcher: MessageDispatcher
): SlackEventBuilder {

    private val slackConfig = Slack.getInstance().config
    override fun simpleTextRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                   simpleString: String, commandType: CommandType): SendSlackMessageEvent {
        val layout = this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
        return this.buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType,
            layout = layout, replaceOriginal = false
        )
    }

    override fun simpleEphemeralTextRequest(textMessage: String, commandBasicInfo: CommandBasicInfo,
                                            commandType: CommandType, commandDetailType: CommandDetailType,
                                            targetUserId: String?): SendSlackMessageEvent {
        val layout = this.templateBuilder.onlyTextTemplate(message = textMessage, isMarkDown = true)
        return this.buildEphemeralMessage(commandDetailType = commandDetailType,
            commandType = commandType, commandBasicInfo = commandBasicInfo, layout = layout, replaceOriginal = false,
            targetUserId = targetUserId)
    }

    override fun detailErrorTextRequest(commandDetailType: CommandDetailType, errorClassName: String, errorMessage: String, details: String?, commandType: CommandType,
                                        commandBasicInfo: CommandBasicInfo): SendSlackMessageEvent{
        val errorHeaderText = "Error : $errorClassName"
        val layout = this.templateBuilder.errorNoticeTemplate(headLineText = errorHeaderText, errorMessage = errorMessage, details = details)
        return this.buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType,
            layout = layout, replaceOriginal = false
        )
    }

    override fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                           timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): SendSlackMessageEvent{
        val layout = this.templateBuilder.simpleScheduleNoticeTemplate( headLineText = headLineText, timeScheduleInfo = timeScheduleInfo )
        return this.buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType,
            layout = layout, replaceOriginal = false
        )
    }

    override fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, commandBasicInfo: CommandBasicInfo,
                                          approvalContents: ApprovalContents, commandType: CommandType, targetUserId: String?): SendSlackMessageEvent{
        val layout = this.templateBuilder.approvalTemplate(
            headLineText = approvalContents.headLineText, approvalContents = approvalContents,
            idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
        )
        return this.buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false,
            targetUserId = targetUserId
        )
    }

    override fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                  selectionFields: List<SelectionContents>, commandType: CommandType,
                                           reasonInput: TextInputContents?, approvalContents: ApprovalContents?): SendSlackMessageEvent{
        val layout = this.templateBuilder.requestApprovalFormTemplate(headLineText = headLineText,
            selectionFields = selectionFields, reasonInput = reasonInput,
            approvalContents = approvalContents ?: ApprovalContents(reason = "Request Approval", approvalButtonName = "Send", rejectButtonName = "Cancel",
                idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType, publisherId = commandBasicInfo.publisherId))

        return this.buildMessage(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType,
            layout = layout, replaceOriginal = false
        )
    }

    override fun requestMeetingFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType,
                                           approvalContents: ApprovalContents?): SendSlackMessageEvent{
        val layout = this.templateBuilder.requestMeetingFormTemplate(
            approvalContents = approvalContents ?: ApprovalContents(
                idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
                reason = "Request Meeting", publisherId = commandBasicInfo.publisherId
            )
        )
        return this.buildEphemeralMessage(commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
            commandType = commandType, layout = layout, replaceOriginal = false)
    }

    override fun replaceOriginalText(
        markdownText: String,
        responseUrl: String,
        commandBasicInfo: CommandBasicInfo,
        commandType: CommandType,
        commandDetailType: CommandDetailType
    ): SendSlackMessageEvent {
        val layout = this.templateBuilder.onlyTextTemplate(message = markdownText, isMarkDown = true)
        return this.buildActionResponse(commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType,
            layout = layout, replaceOriginal = true, responseUrl = responseUrl)
    }

    // task form request added.
    override fun requestTaskFormRequest(
        commandBasicInfo: CommandBasicInfo,
        commandType: CommandType,
        commandDetailType: CommandDetailType
    ): SendSlackMessageEvent {
        // request task form template
        val layout = this.templateBuilder.requestTaskFormTemplate()
        TODO("Not yet Done")
        return this.buildEphemeralMessage(commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
            commandType = commandType, layout = layout, replaceOriginal = false)
    }

    private fun buildMessage(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                             commandType: CommandType, layout: LayoutBlocks, replaceOriginal: Boolean,
                             targetUserId: String? = null): SendSlackMessageEvent {
        val messageType = if(targetUserId == null) MessageType.CHANNEL_ALERT else MessageType.DIRECT_MESSAGE
        val payload = this.toEventContents(commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
            replaceOriginal = replaceOriginal,
            body = this.extractBodyData(
                chatPostMessageRequest = this.chatPostMessageBuilder(channel = commandBasicInfo.channel, blocks = layout.template,
                    idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
                    targetUserId = targetUserId)),
            messageType = messageType
        )
        return SendSlackMessageEvent(
            idempotencyKey = commandBasicInfo.idempotencyKey,
            payload = payload,
            destination = "",
            timestamp = 1L,
            eventType = messageType
        )
    }

    private fun buildEphemeralMessage(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                      commandType: CommandType, layout: LayoutBlocks, replaceOriginal: Boolean,
                                      targetUserId: String? = null): SendSlackMessageEvent{
        val payload = this.toEventContents(
            commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
            replaceOriginal = replaceOriginal,
            body = this.extractBodyData(
                chatPostEphemeralRequest = this.chatPostEphemeralBuilder(channel = targetUserId ?: commandBasicInfo.channel, blocks = layout.template,
                    idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
                    userId = targetUserId ?: commandBasicInfo.publisherId)
            ),
            messageType = MessageType.EPHEMERAL_MESSAGE
        )
        return SendSlackMessageEvent(
            idempotencyKey = commandBasicInfo.idempotencyKey,
            payload = payload,
            destination = "",
            timestamp = 1L,
            eventType = MessageType.EPHEMERAL_MESSAGE
        )
    }

    private fun buildActionResponse(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                    commandType: CommandType, layout: LayoutBlocks, replaceOriginal: Boolean,
                                    responseUrl: String): SendSlackMessageEvent {
        val payload = this.toEventContents(commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
            body = this.toSnakeCaseJsonString(
                ActionResponse.builder()
                    .blocks(layout.template).replaceOriginal(replaceOriginal).build()
            ),
            responseUrl = responseUrl
        )
        return SendSlackMessageEvent(
            idempotencyKey = commandBasicInfo.idempotencyKey,
            payload = payload,
            destination = "",
            timestamp = 1L,
            eventType = MessageType.ACTION_RESPONSE
        )
    }

    private fun extractBodyData(chatPostEphemeralRequest: ChatPostEphemeralRequest) =
        this.toMap(formBody = RequestFormBuilder.toForm(chatPostEphemeralRequest).build())

    private fun extractBodyData(chatPostMessageRequest: ChatPostMessageRequest) =
        this.toMap(formBody = RequestFormBuilder.toForm(chatPostMessageRequest).build())

    private fun toSnakeCaseJsonString(actionResponse: ActionResponse) =
        GsonFactory.createSnakeCase(this.slackConfig).toJson(actionResponse)

    private fun toEventContents(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                body: Map<String, String>, replaceOriginal: Boolean, messageType: MessageType) =
        PostEventPayloadContents(
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

    private fun toEventContents(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                responseUrl: String, body: String) =
        ActionEventPayloadContents(
            apiAppId = commandBasicInfo.appId,
            commandDetailType = commandDetailType,
            idempotencyKey = commandBasicInfo.idempotencyKey,
            publisherId = commandBasicInfo.publisherId,
            channel = commandBasicInfo.channel,
            responseUrl = responseUrl,
            body = body,
            eventId = UUID.randomUUID()
        )

    private fun toMap(formBody: FormBody): Map<String, String>
        = (0 until formBody.size).associate { formBody.name(it) to formBody.value(it) }


    //https://api.slack.com/methods/chat.postMessage
    private fun chatPostMessageBuilder(commandDetailType: CommandDetailType, idempotencyKey: UUID, channel: String, blocks: List<LayoutBlock>,
                                       targetUserId: String? = null) =
        ChatPostMessageRequest.builder().channel(targetUserId ?: channel).text("${idempotencyKey},${commandDetailType}")
            .token(this.botToken).blocks(blocks).build()

    //FIXME Ephemeral message cannot include any texts with blocks field
    private fun chatPostEphemeralBuilder(commandDetailType: CommandDetailType, idempotencyKey: UUID, channel: String,
                                         blocks: List<LayoutBlock>, userId: String) =
        ChatPostEphemeralRequest.builder()
            .channel(channel).text("$idempotencyKey, $commandDetailType")
            .token(this.botToken).blocks(blocks).user(userId)
            .build()
}