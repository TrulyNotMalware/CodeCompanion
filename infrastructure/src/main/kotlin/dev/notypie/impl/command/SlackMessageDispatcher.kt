package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.LayoutBlock

class SlackMessageDispatcher(
    private val botToken: String
) {
    private val slack: Slack = Slack.getInstance()

    fun dispatch(){
//        this.slack.methods(botToken).chatPostMessage()
    }

    //https://api.slack.com/methods/chat.postMessage
    private fun chatPostMessageBuilder(idempotencyKey: String, channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel).text(idempotencyKey)
            .token(this.botToken).blocks(blocks).build()
}