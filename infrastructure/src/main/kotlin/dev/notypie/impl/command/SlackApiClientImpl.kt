package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks

class SlackApiClientImpl(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder
): SlackApiRequester {

    private val slack: Slack = Slack.getInstance()

    override fun simpleTextRequest(headLineText: String, channel: String, simpleString: String, commandType: CommandType): SlackApiResponse {
        val layout = this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return returnResponse(result = result, commandType = commandType)
    }

    override fun errorTextRequest(errorClassName: String, channel: String, errorMessage: String, details: String?, commandType: CommandType): SlackApiResponse{
        val errorHeaderText = "Error : $errorClassName"
        val layout = this.templateBuilder.errorNoticeTemplate(headLineText = errorHeaderText, errorMessage = errorMessage, details = details)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return returnResponse(result = result, commandType = commandType)
    }

    override fun simpleTimeScheduleRequest(headLineText: String, channel: String,  timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): SlackApiResponse{
        val layout = this.templateBuilder.simpleScheduleNoticeTemplate( headLineText = headLineText, timeScheduleInfo = timeScheduleInfo )
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return this.returnResponse(result = result, commandType = commandType)
    }

    override fun simpleApplyRejectRequest(headLineText: String, channel: String, approvalContents: ApprovalContents, commandType: CommandType): SlackApiResponse{
        val layout = this.templateBuilder.approvalTemplate(headLineText = headLineText, approvalContents = approvalContents)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return this.returnResponse(result = result, states = layout.interactionStates, commandType = commandType)
    }

    override fun simpleApprovalFormRequest(headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents?, commandType: CommandType): SlackApiResponse{
        val layout = this.templateBuilder.requestApprovalFormTemplate(headLineText = headLineText,
            selectionFields = selectionFields, reasonInput = reasonInput)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return this.returnResponse(result = result, states = layout.interactionStates, commandType = commandType)
    }

    private fun doAction(channel: String, layout: LayoutBlocks): ChatPostMessageResponse
    = this.slack.methods(botToken).chatPostMessage(this.chatPostMessageBuilder(channel = channel, blocks = layout.template))

    private fun chatPostMessageBuilder(channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel)
            .token(this.botToken).blocks(blocks).build()

    private fun returnResponse(result: ChatPostMessageResponse, commandType: CommandType, states: List<States> = listOf()): SlackApiResponse{
        //Result is false.
        if(!result.isOk) this.errorTextRequest(errorClassName = this::class.simpleName ?: "SlackApiClientImpl",
                channel = result.channel, errorMessage = "Request ${result.isOk}", details = result.message.toString(), commandType = CommandType.SIMPLE)

        return SlackApiResponse(ok = result.isOk, apiAppId = result.message.appId, publisherId = result.message.user,
            channel = result.channel, actionStates = states, commandType = commandType)
    }
}