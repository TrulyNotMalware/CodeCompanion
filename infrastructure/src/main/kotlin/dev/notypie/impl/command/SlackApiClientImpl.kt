package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.methods.Methods
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackEventContents
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks

class SlackApiClientImpl(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder
): SlackApiRequester {

    private val slack: Slack = Slack.getInstance()

    @Deprecated(message = "")
    override fun buildSimpleTextRequestBody(headLineText: String, channel: String, simpleString: String): SlackEventContents{
        val res = SlackEventContents(ok = true, type = Methods.CHAT_POST_MESSAGE, data =
        ChatPostMessageRequest.builder()
            .channel(channel).token(this.botToken).blocks(
                this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true).template
            )
            .build())
        return res
    }

    override fun simpleTextRequest(headLineText: String, channel: String, simpleString: String): SlackApiResponse {
        val layout = this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return returnResponse(result = result)
    }

    override fun errorTextRequest(errorClassName: String, channel: String, errorMessage: String, details: String?): SlackApiResponse{
        val errorHeaderText = "Error : $errorClassName"
        val layout = this.templateBuilder.errorNoticeTemplate(headLineText = errorHeaderText, errorMessage = errorMessage, details = details)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
//        return SlackApiResponse(ok = result.isOk, channel = channel)
        return returnResponse(result = result)
    }

    override fun simpleTimeScheduleRequest(headLineText: String, channel: String,  timeScheduleInfo: TimeScheduleInfo): SlackApiResponse{
        val layout = this.templateBuilder.simpleScheduleNoticeTemplate( headLineText = headLineText, timeScheduleInfo = timeScheduleInfo )
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return this.returnResponse(result = result)
    }

    override fun simpleApplyRejectRequest(headLineText: String, channel: String, approvalContents: ApprovalContents): SlackApiResponse{
        val layout = this.templateBuilder.approvalTemplate(headLineText = headLineText, approvalContents = approvalContents)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return this.returnResponse(result = result)
    }

    override fun simpleApprovalFormRequest(headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents?): SlackApiResponse{
        val layout = this.templateBuilder.requestApprovalFormTemplate(headLineText = headLineText,
            selectionFields = selectionFields, reasonInput = reasonInput)
        val result: ChatPostMessageResponse = this.doAction(channel = channel, layout = layout)
        return this.returnResponse(result = result)
    }

    private fun doAction(channel: String, layout: LayoutBlocks): ChatPostMessageResponse
    = this.slack.methods(botToken).chatPostMessage(this.chatPostMessageBuilder(channel = channel, blocks = layout.template))

    private fun chatPostMessageBuilder(channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel)
            .token(this.botToken).blocks(blocks).build()

    private fun returnResponse(result: ChatPostMessageResponse): SlackApiResponse{
        //Result is false.
        if(!result.isOk) this.errorTextRequest(errorClassName = this::class.simpleName ?: "SlackApiClientImpl",
                channel = result.channel, errorMessage = "Request ${result.isOk}", details = result.message.toString(),)

        return SlackApiResponse(ok = result.isOk, apiAppId = result.message.appId, publisherId = result.message.user,
            channel = result.channel)
    }
}