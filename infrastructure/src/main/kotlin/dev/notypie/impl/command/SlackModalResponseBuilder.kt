package dev.notypie.impl.command

import com.slack.api.methods.Methods
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.domain.command.dto.SlackEventContents

class SlackModalResponseBuilder: SlackResponseBuilder{

    override fun buildRequestBody(channel: String, simpleString: String): SlackEventContents
    = SlackEventContents(ok = true, type = Methods.CHAT_POST_MESSAGE
        , data = ChatPostMessageRequest.builder()
            .channel(channel).text(simpleString)
            .build()
    )

}