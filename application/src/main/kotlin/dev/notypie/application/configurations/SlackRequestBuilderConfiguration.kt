package dev.notypie.application.configurations

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.SlackApiClientImpl
import dev.notypie.impl.command.SlackInteractionRequestParser
import dev.notypie.impl.command.SlackMessageDispatcher
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackTemplateBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SlackRequestBuilderConfiguration(
    @Value("\${slack.api.token}") val botToken: String,
) {

    @Bean
    fun slackTemplateBuilder() : SlackTemplateBuilder = ModalTemplateBuilder()

    @Bean
    fun slackMessageDispatcher() = SlackMessageDispatcher(botToken = botToken)

    @Bean
    fun slackRequestBuilder(slackTemplateBuilder: SlackTemplateBuilder,
                            applicationEventPublisher: ApplicationEventPublisher,
                            slackMessageDispatcher: SlackMessageDispatcher): SlackApiRequester = SlackApiClientImpl(
        botToken = botToken, templateBuilder = slackTemplateBuilder, applicationEventPublisher =  applicationEventPublisher,
        slackMessageDispatcher = slackMessageDispatcher
    )

    @Bean
    fun interactionRequestParser(): InteractionPayloadParser = SlackInteractionRequestParser()
}