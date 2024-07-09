package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.methods.Methods
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackEventContents
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
        return SlackApiResponse(ok = result.isOk, channel = channel)
    }

    private fun chatPostMessageBuilder(channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel)
            .token(this.botToken).blocks(blocks).build()
}