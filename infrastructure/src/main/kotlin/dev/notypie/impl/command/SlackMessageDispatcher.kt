package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.model.block.LayoutBlock
import dev.notypie.repository.outbox.SlackPostRequestMessage
import org.springframework.context.ApplicationEventPublisher

class SlackMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher
) {
    private val slack: Slack = Slack.getInstance()

    fun dispatch(slackPostRequestMessage: SlackPostRequestMessage){
        this.applicationEventPublisher.publishEvent(slackPostRequestMessage)
    }

    //https://api.slack.com/methods/chat.postMessage
    private fun chatPostMessageBuilder(idempotencyKey: String, channel: String, blocks: List<LayoutBlock>) =
        ChatPostMessageRequest.builder().channel(channel).text(idempotencyKey)
            .token(this.botToken).blocks(blocks).build()
}