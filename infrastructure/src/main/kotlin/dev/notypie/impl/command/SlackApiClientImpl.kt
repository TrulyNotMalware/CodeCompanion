package dev.notypie.impl.command

import com.fasterxml.jackson.module.kotlin.readValue
import com.slack.api.Slack
import com.slack.api.app_backend.interactive_components.ActionResponseSender
import com.slack.api.app_backend.interactive_components.response.ActionResponse
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.model.block.LayoutBlock
import com.slack.api.webhook.WebhookResponse
import dev.notypie.common.objectMapper
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status
import dev.notypie.repository.outbox.SlackPostRequestMessage
import dev.notypie.repository.outbox.SlackRequestType
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks

class SlackApiClientImpl(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder,
    private val slackMessageDispatcher: SlackMessageDispatcher
): SlackApiRequester {

    private val slack: Slack = Slack.getInstance()
    private val webHookSender = ActionResponseSender(this.slack)
    override fun doNothing(): SlackApiResponse = SlackApiResponse.empty()

    override fun simpleTextRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String,
                                   simpleString: String, commandType: CommandType): SlackApiResponse {
        val layout = this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
        val result: ChatPostMessageResponse = this.doAction(idempotencyKey = idempotencyKey,
            channel = channel, layout = layout, commandDetailType = commandDetailType)
        return returnResponse(result = result, commandType = commandType, idempotencyKey = idempotencyKey)
    }

    override fun simpleEphemeralTextRequest(textMessage: String, commandBasicInfo: CommandBasicInfo,
                                            commandType: CommandType, commandDetailType: CommandDetailType): SlackApiResponse {
        val layout = this.templateBuilder.onlyTextTemplate(message = textMessage, isMarkDown = true)
        val result = this.doEphemeralAction(commandDetailType = commandDetailType,
            channel = commandBasicInfo.channel, layout = layout,
            idempotencyKey = commandBasicInfo.idempotencyKey, publisherId = commandBasicInfo.publisherId)
        return this.returnEphemeralResponse(idempotencyKey = commandBasicInfo.idempotencyKey,
            channel = commandBasicInfo.channel, result = result, publisherId = commandBasicInfo.publisherId, commandType = commandType,
            apiAppId = commandBasicInfo.appId)
    }

    override fun detailErrorTextRequest(commandDetailType: CommandDetailType, idempotencyKey: String, errorClassName: String, channel: String, errorMessage: String, details: String?, commandType: CommandType): SlackApiResponse{
        val errorHeaderText = "Error : $errorClassName"
        val layout = this.templateBuilder.errorNoticeTemplate(headLineText = errorHeaderText, errorMessage = errorMessage, details = details)
        val result: ChatPostMessageResponse = this.doAction(idempotencyKey = idempotencyKey,
            channel = channel, layout = layout, commandDetailType = commandDetailType)
        return returnResponse(result = result, commandType = commandType, idempotencyKey = idempotencyKey)
    }

    override fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String,
                                           timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): SlackApiResponse{
        val layout = this.templateBuilder.simpleScheduleNoticeTemplate( headLineText = headLineText, timeScheduleInfo = timeScheduleInfo )
        val result: ChatPostMessageResponse = this.doAction(idempotencyKey = idempotencyKey,
            channel = channel, layout = layout, commandDetailType = commandDetailType)
        return this.returnResponse(result = result, commandType = commandType, idempotencyKey = idempotencyKey)
    }

    override fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String,
                                          approvalContents: ApprovalContents, commandType: CommandType): SlackApiResponse{
        val layout = this.templateBuilder.approvalTemplate(headLineText = headLineText, approvalContents = approvalContents)
        val result: ChatPostMessageResponse = this.doAction(idempotencyKey = idempotencyKey,
            channel = channel, layout = layout, commandDetailType = commandDetailType)
        return this.returnResponse(result = result, states = layout.interactionStates, commandType = commandType, idempotencyKey = idempotencyKey)
    }

    override fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents?, commandType: CommandType): SlackApiResponse{
        val layout = this.templateBuilder.requestApprovalFormTemplate(headLineText = headLineText,
            selectionFields = selectionFields, reasonInput = reasonInput)
        val result: ChatPostMessageResponse = this.doAction(idempotencyKey = idempotencyKey,
            channel = channel, layout = layout, commandDetailType = commandDetailType)
        return this.returnResponse(result = result, states = layout.interactionStates, commandType = commandType, idempotencyKey = idempotencyKey)
    }

    override fun requestMeetingFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): SlackApiResponse{
        val layout = this.templateBuilder.requestMeetingFormTemplate(
            commandDetailType = commandDetailType, idempotencyKey = commandBasicInfo.idempotencyKey
        )
        val result: ChatPostEphemeralResponse = this.doEphemeralAction(
            commandDetailType = commandDetailType, idempotencyKey = commandBasicInfo.idempotencyKey,
            channel = commandBasicInfo.channel, layout = layout, publisherId = commandBasicInfo.publisherId
        )
        return this.returnEphemeralResponse(result = result, states = layout.interactionStates,
            commandType = commandType, idempotencyKey = commandBasicInfo.idempotencyKey,
            channel = commandBasicInfo.channel, apiAppId = commandBasicInfo.appId, publisherId = commandBasicInfo.publisherId
        )
    }



    override fun replaceOriginalText(
        markdownText: String,
        responseUrl: String,
        commandBasicInfo: CommandBasicInfo,
        commandType: CommandType,
        commandDetailType: CommandDetailType
    ): SlackApiResponse {
        val layout = this.templateBuilder.onlyTextTemplate(message = markdownText, isMarkDown = true)
        val response = ActionResponse.builder()
            .blocks(layout.template).replaceOriginal(true).build()
        val result = this.webHookSender.send(responseUrl, response)
        return this.returnResponse(commandBasicInfo = commandBasicInfo, result = result, commandType = commandType)
    }

    private fun doAction(commandDetailType: CommandDetailType, idempotencyKey: String, channel: String, layout: LayoutBlocks): ChatPostMessageResponse
    = this.slack.methods(botToken).chatPostMessage(
        this.chatPostMessageBuilder(channel = channel, blocks = layout.template,
            idempotencyKey = idempotencyKey, commandDetailType = commandDetailType)
    )

    private fun doEphemeralAction(commandDetailType: CommandDetailType, idempotencyKey: String, channel: String,
                                  layout: LayoutBlocks, publisherId: String): ChatPostEphemeralResponse
    = this.slack.methods(botToken).chatPostEphemeral(
        this.chatPostEphemeralBuilder(channel = channel, blocks = layout.template,
            idempotencyKey = idempotencyKey, commandDetailType = commandDetailType,
            publisherId = publisherId)
    )

    //https://api.slack.com/methods/chat.postMessage
    private fun chatPostMessageBuilder(commandDetailType: CommandDetailType, idempotencyKey: String, channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel).text("${idempotencyKey},${commandDetailType}")
            .token(this.botToken).blocks(blocks).build()

    //FIXME Ephemeral message cannot include any texts with blocks field
    private fun chatPostEphemeralBuilder(commandDetailType: CommandDetailType, idempotencyKey: String, channel: String,
                                         blocks: List<LayoutBlock>, publisherId: String) =
        ChatPostEphemeralRequest.builder()
            .channel(channel).text("${idempotencyKey}, ${commandDetailType.toString()}")
            .token(this.botToken).blocks(blocks).user(publisherId)
            .build()

    @Deprecated(message = "for removal")
    private fun toSlackPostRequestMessage(type: SlackRequestType, layouts: List<LayoutBlock>):SlackPostRequestMessage {
        val toStringContents = objectMapper.writeValueAsString(layouts)
        val map: Map<String, Any> = objectMapper.readValue(toStringContents)
        return SlackPostRequestMessage(
            type = type,
            payload = map
        )
    }

    private fun returnResponse(idempotencyKey: String, result: ChatPostMessageResponse, commandType: CommandType, states: List<States> = listOf()): SlackApiResponse{
        //Result is false.
        if(!result.isOk) this.detailErrorTextRequest(errorClassName = this::class.simpleName ?: "SlackApiClientImpl",
                channel = result.channel, errorMessage = "Request ${result.isOk}", details = result.message.toString(),
            commandType = CommandType.SIMPLE, idempotencyKey = idempotencyKey, commandDetailType = CommandDetailType.ERROR_RESPONSE)

        return SlackApiResponse(ok = result.isOk, apiAppId = result.message.appId, publisherId = result.message.user,
            channel = result.channel, actionStates = states, commandType = commandType, idempotencyKey = idempotencyKey,
            status = if(result.isOk) Status.SUCCESS else Status.FAILED
        )
    }

    private fun returnResponse(commandBasicInfo: CommandBasicInfo, result: WebhookResponse, commandType: CommandType ): SlackApiResponse{
        return SlackApiResponse(
            ok = result.code in 200..299,
            apiAppId = commandBasicInfo.appId, channel = commandBasicInfo.channel, commandType = commandType,
            idempotencyKey = commandBasicInfo.idempotencyKey, publisherId = commandBasicInfo.publisherId,
            status = if(result.code in 200..299) Status.SUCCESS else Status.FAILED
        )
    }

    private fun returnEphemeralResponse(idempotencyKey: String, result: ChatPostEphemeralResponse, commandType: CommandType, states: List<States> = listOf(),
                               channel: String, apiAppId: String, publisherId: String): SlackApiResponse{
        if(!result.isOk) this.detailErrorTextRequest(errorClassName = this::class.simpleName ?: "SlackApiClientImpl",
            channel = channel, errorMessage = "Request ${result.isOk}", details = result.error,
            commandType = CommandType.SIMPLE, idempotencyKey = idempotencyKey, commandDetailType = CommandDetailType.ERROR_RESPONSE)

        return SlackApiResponse(ok = result.isOk, apiAppId = apiAppId, publisherId = publisherId,
            channel = channel, actionStates = states, commandType = commandType, idempotencyKey = idempotencyKey,
            status = if(result.isOk) Status.SUCCESS else Status.FAILED
        )
    }
}