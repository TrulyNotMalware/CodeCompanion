package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.app_backend.interactive_components.response.ActionResponse
import com.slack.api.methods.RequestFormBuilder
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.LayoutBlock
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.ActionEventContents
import dev.notypie.domain.command.dto.MessageType
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.PostEventContents
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks
import okhttp3.FormBody

class SlackApiClientImpl(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder,
    private val messageDispatcher: MessageDispatcher
): SlackApiRequester {

    private val slackConfig = Slack.getInstance().config
    override fun simpleTextRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                   simpleString: String, commandType: CommandType): CommandOutput {
        val layout = this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
        return this.doAction(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false
        )
    }

    override fun simpleEphemeralTextRequest(textMessage: String, commandBasicInfo: CommandBasicInfo,
                                            commandType: CommandType, commandDetailType: CommandDetailType,
                                            targetUserId: String?): CommandOutput {
        val layout = this.templateBuilder.onlyTextTemplate(message = textMessage, isMarkDown = true)
        return this.doEphemeralAction(commandDetailType = commandDetailType,
            commandType = commandType, commandBasicInfo = commandBasicInfo, layout = layout, replaceOriginal = false,
            targetUserId = targetUserId)
    }

    override fun detailErrorTextRequest(commandDetailType: CommandDetailType, errorClassName: String, errorMessage: String, details: String?, commandType: CommandType,
                                        commandBasicInfo: CommandBasicInfo): CommandOutput{
        val errorHeaderText = "Error : $errorClassName"
        val layout = this.templateBuilder.errorNoticeTemplate(headLineText = errorHeaderText, errorMessage = errorMessage, details = details)
        return this.doAction(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false
        )
    }

    override fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                           timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): CommandOutput{
        val layout = this.templateBuilder.simpleScheduleNoticeTemplate( headLineText = headLineText, timeScheduleInfo = timeScheduleInfo )
        return this.doAction(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false
        )
    }

    override fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, commandBasicInfo: CommandBasicInfo,
                                          approvalContents: ApprovalContents, commandType: CommandType, targetUserId: String?): CommandOutput{
        val layout = this.templateBuilder.approvalTemplate(
            headLineText = approvalContents.headLineText, approvalContents = approvalContents,
            idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
        )
        return this.doAction(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false,
            targetUserId = targetUserId
        )
    }

    override fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                  selectionFields: List<SelectionContents>, commandType: CommandType,
                                           reasonInput: TextInputContents?, approvalContents: ApprovalContents?): CommandOutput{
        val layout = this.templateBuilder.requestApprovalFormTemplate(headLineText = headLineText,
            selectionFields = selectionFields, reasonInput = reasonInput,
            approvalContents = approvalContents ?: ApprovalContents(reason = "Request Approval", approvalButtonName = "Send", rejectButtonName = "Cancel",
                idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType))
        return this.doAction(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false
        )
    }

    override fun requestMeetingFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType,
                                           approvalContents: ApprovalContents?): CommandOutput{
        val layout = this.templateBuilder.requestMeetingFormTemplate(
            approvalContents = approvalContents ?: ApprovalContents(
                idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType, reason = "Request Meeting",
            )
        )
        return this.doEphemeralAction(
            commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType, layout = layout, replaceOriginal = false
        )
    }

    override fun replaceOriginalText(
        markdownText: String,
        responseUrl: String,
        commandBasicInfo: CommandBasicInfo,
        commandType: CommandType,
        commandDetailType: CommandDetailType
    ): CommandOutput {
        val layout = this.templateBuilder.onlyTextTemplate(message = markdownText, isMarkDown = true)
        return this.doActionResponse(commandBasicInfo = commandBasicInfo,
            commandDetailType = commandDetailType, commandType = commandType,
            layout = layout, replaceOriginal = true, responseUrl = responseUrl)
    }

    private fun doAction( commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                          commandType: CommandType, layout: LayoutBlocks, replaceOriginal: Boolean,
                          targetUserId: String? = null) =
        this.messageDispatcher.dispatch(
            event = this.toEventContents(commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
                replaceOriginal = replaceOriginal,
                body = this.extractBodyData(
                    this.chatPostMessageBuilder(channel = commandBasicInfo.channel, blocks = layout.template,
                        idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
                        targetUserId = targetUserId)),
                messageType = MessageType.TO_ALL
            ),
            commandType = commandType
        )


    private fun doEphemeralAction(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                  commandType: CommandType, layout: LayoutBlocks, replaceOriginal: Boolean,
                                  targetUserId: String? = null) =
        this.messageDispatcher.dispatch(
            event = this.toEventContents(
                commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
                replaceOriginal = replaceOriginal,
                body = this.extractBodyData(
                    this.chatPostEphemeralBuilder(channel = targetUserId ?: commandBasicInfo.channel, blocks = layout.template,
                        idempotencyKey = commandBasicInfo.idempotencyKey, commandDetailType = commandDetailType,
                        userId = targetUserId ?: commandBasicInfo.publisherId)
                ),
                messageType = MessageType.EPHEMERAL
            ),
            commandType = commandType
        )

    private fun doActionResponse(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                 commandType: CommandType, layout: LayoutBlocks, replaceOriginal: Boolean,
                                 responseUrl: String) =
        this.messageDispatcher.dispatch(
            event = this.toEventContents(commandBasicInfo = commandBasicInfo, commandDetailType = commandDetailType,
                body = this.toSnakeCaseJsonString(
                    ActionResponse.builder()
                        .blocks(layout.template).replaceOriginal(replaceOriginal).build()
                ),
                responseUrl = responseUrl
            ),
            commandType = commandType,

        )

    private fun extractBodyData(chatPostEphemeralRequest: ChatPostEphemeralRequest) =
        this.toMap(RequestFormBuilder.toForm(chatPostEphemeralRequest).build())

    private fun extractBodyData(chatPostMessageRequest: ChatPostMessageRequest) =
        this.toMap(RequestFormBuilder.toForm(chatPostMessageRequest).build())

    private fun toSnakeCaseJsonString(actionResponse: ActionResponse) =
        GsonFactory.createSnakeCase(this.slackConfig).toJson(actionResponse)

    private fun toEventContents(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                body: Map<String, String>, replaceOriginal: Boolean, messageType: MessageType) =
        PostEventContents(
            messageType = messageType,
            apiAppId = commandBasicInfo.appId,
            commandDetailType = commandDetailType,
            body = body,
            idempotencyKey = commandBasicInfo.idempotencyKey,
            publisherId = commandBasicInfo.publisherId,
            replaceOriginal = replaceOriginal,
            channel = commandBasicInfo.channel
        )

    private fun toEventContents(commandBasicInfo: CommandBasicInfo, commandDetailType: CommandDetailType,
                                responseUrl: String, body: String) =
        ActionEventContents(
            apiAppId = commandBasicInfo.appId,
            commandDetailType = commandDetailType,
            idempotencyKey = commandBasicInfo.idempotencyKey,
            publisherId = commandBasicInfo.publisherId,
            channel = commandBasicInfo.channel,
            responseUrl = responseUrl,
            body = body
        )

    private fun toMap(formBody: FormBody): Map<String, String>
        = (0 until formBody.size).associate { formBody.name(it) to formBody.value(it) }


    //https://api.slack.com/methods/chat.postMessage
    private fun chatPostMessageBuilder(commandDetailType: CommandDetailType, idempotencyKey: String, channel: String, blocks: List<LayoutBlock>,
                                       targetUserId: String? = null) =
        ChatPostMessageRequest.builder().channel(targetUserId ?: channel).text("${idempotencyKey},${commandDetailType}")
            .token(this.botToken).blocks(blocks).build()

    //FIXME Ephemeral message cannot include any texts with blocks field
    private fun chatPostEphemeralBuilder(commandDetailType: CommandDetailType, idempotencyKey: String, channel: String,
                                         blocks: List<LayoutBlock>, userId: String) =
        ChatPostEphemeralRequest.builder()
            .channel(channel).text("$idempotencyKey, $commandDetailType")
            .token(this.botToken).blocks(blocks).user(userId)
            .build()
}