package dev.notypie.application.configurations

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.SlackApiClientImpl
import dev.notypie.impl.command.SlackInteractionRequestParser
import dev.notypie.impl.command.SlackMessageDispatcher
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
    @ConditionalOnMissingBean(SlackMessageDispatcher::class)
    fun slackMessageDispatcher(
        applicationEventPublisher: ApplicationEventPublisher,
    ) = SlackMessageDispatcher(botToken = botToken, applicationEventPublisher = applicationEventPublisher)

    @Bean
    @ConditionalOnMissingBean(SlackApiRequester::class)
    fun slackRequestBuilder(slackTemplateBuilder: SlackTemplateBuilder,
                            applicationEventPublisher: ApplicationEventPublisher,
                            slackMessageDispatcher: SlackMessageDispatcher): SlackApiRequester = SlackApiClientImpl(
        botToken = botToken, templateBuilder = slackTemplateBuilder,
        slackMessageDispatcher = slackMessageDispatcher
    )

    @Bean
    @ConditionalOnMissingBean(InteractionPayloadParser::class)
    fun interactionRequestParser(): InteractionPayloadParser = SlackInteractionRequestParser()
}