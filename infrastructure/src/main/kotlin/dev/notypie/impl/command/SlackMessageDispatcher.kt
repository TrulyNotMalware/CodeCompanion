package dev.notypie.impl.command

import dev.notypie.repository.outbox.SlackPostRequestMessage
import org.springframework.context.ApplicationEventPublisher

class SlackMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher
) {

    fun dispatch(slackPostRequestMessage: SlackPostRequestMessage){
        this.applicationEventPublisher.publishEvent(slackPostRequestMessage)
    }
    
}