package dev.notypie.application.configurations

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.SlackApiClientImpl
import dev.notypie.impl.command.SlackInteractionRequestParser
import dev.notypie.impl.command.ApplicationMessageDispatcher
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackTemplateBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SlackRequestBuilderConfiguration(
    @Value("\${slack.api.token}") val botToken: String,
) {

    @Bean
    @ConditionalOnMissingBean(SlackTemplateBuilder::class)
    fun slackTemplateBuilder() : SlackTemplateBuilder = ModalTemplateBuilder()

    @Bean
    @ConditionalOnMissingBean(MessageDispatcher::class)
    fun messageDispatcher(
        applicationEventPublisher: ApplicationEventPublisher,
    ) = ApplicationMessageDispatcher(botToken = botToken, applicationEventPublisher = applicationEventPublisher)

    @Bean
    @ConditionalOnMissingBean(SlackApiRequester::class)
    fun slackRequestBuilder(slackTemplateBuilder: SlackTemplateBuilder,
                            applicationEventPublisher: ApplicationEventPublisher,
                            slackMessageDispatcher: MessageDispatcher): SlackApiRequester = SlackApiClientImpl(
        botToken = botToken, templateBuilder = slackTemplateBuilder,
        messageDispatcher = slackMessageDispatcher
    )

    @Bean
    @ConditionalOnMissingBean(InteractionPayloadParser::class)
    fun interactionRequestParser(): InteractionPayloadParser = SlackInteractionRequestParser()
}