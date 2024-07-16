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
import dev.notypie.slack.SlackTemplateBuilder

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
                this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
            )
            .build())
        return res
    }

    override fun simpleTextRequest(headLineText: String, channel: String, simpleString: String): SlackApiResponse {
        val result: ChatPostMessageResponse = slack.methods(botToken).chatPostMessage(
            this.chatPostMessageBuilder(channel = channel,
                blocks = this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = true)
            )
        )
        return returnResponse(result = result)
    }

    override fun errorTextRequest(errorClassName: String, channel: String, errorMessage: String, details: String?): SlackApiResponse{
        val errorHeaderText = "Error : $errorClassName"
        val result: ChatPostMessageResponse = slack.methods(this.botToken).chatPostMessage(
            this.chatPostMessageBuilder(channel = channel,
                blocks = this.templateBuilder.errorNoticeTemplate(
                    headLineText = errorHeaderText, errorMessage = errorMessage, details = details))
        )
        return SlackApiResponse(ok = result.isOk, channel = channel)
    }

    override fun simpleTimeScheduleRequest(headLineText: String, channel: String,  timeScheduleInfo: TimeScheduleInfo): SlackApiResponse{
        val result: ChatPostMessageResponse = slack.methods(botToken).chatPostMessage(
            this.chatPostMessageBuilder(channel = channel,
                blocks = this.templateBuilder.simpleScheduleNoticeTemplate(
                    headLineText = headLineText, timeScheduleInfo = timeScheduleInfo
                ))
        )
        return this.returnResponse(result = result)
    }

    override fun simpleApplyRejectRequest(headLineText: String, channel: String, approvalContents: ApprovalContents): SlackApiResponse{
        val result: ChatPostMessageResponse = this.slack.methods(this.botToken).chatPostMessage(
            this.chatPostMessageBuilder(channel = channel,
                blocks = this.templateBuilder.approvalTemplate(headLineText = headLineText, approvalContents = approvalContents))
        )
        return this.returnResponse(result = result)
    }

    override fun simpleApprovalFormRequest(headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents?): SlackApiResponse{
        val result: ChatPostMessageResponse = this.slack.methods(this.botToken).chatPostMessage(
            this.chatPostMessageBuilder(channel = channel,
                blocks = this.templateBuilder.requestApprovalFormTemplate(
                    headLineText = headLineText, selectionFields = selectionFields, reasonInput = reasonInput)
            )
        )
        return this.returnResponse(result = result)
    }

    private fun chatPostMessageBuilder(channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel)
            .token(this.botToken).blocks(blocks).build()

    private fun returnResponse(result: ChatPostMessageResponse): SlackApiResponse{
        //Result is false.
        if(!result.isOk) this.errorTextRequest(errorClassName = this::class.simpleName ?: "SlackApiClientImpl",
                channel = result.channel, errorMessage = "Request ${result.isOk}", details = result.message.toString(),)

        return SlackApiResponse(ok = result.isOk, channel = result.channel)
    }
}