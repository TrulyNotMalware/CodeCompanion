package dev.notypie.impl.command

import com.slack.api.methods.Methods
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.domain.command.dto.SlackEventContents
import dev.notypie.slack.SlackTemplateBuilder

class SlackRequestBuilderImpl(
    private val botToken: String,
    private val templateBuilder: SlackTemplateBuilder
): SlackRequestBuilder {

    override fun buildSimpleTextRequestBody(headLineText: String, channel: String, simpleString: String): SlackEventContents
    = SlackEventContents(ok = true, type = Methods.CHAT_POST_MESSAGE, data =
        ChatPostMessageRequest.builder()
            .channel(channel).token(this.botToken).blocks(
                this.templateBuilder.simpleTextResponseTemplate(headLineText = headLineText, body = simpleString, isMarkDown = false)
            )
        .build())
}