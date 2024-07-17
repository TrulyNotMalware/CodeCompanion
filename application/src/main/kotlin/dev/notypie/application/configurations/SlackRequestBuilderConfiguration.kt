package dev.notypie.application.configurations

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.impl.command.SlackApiClientImpl
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackTemplateBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SlackRequestBuilderConfiguration {

    @Value("\${slack.api.token}")
    lateinit var botToken: String

    @Bean
    fun slackTemplateBuilder() : SlackTemplateBuilder = ModalTemplateBuilder()

    @Bean
    fun slackRequestBuilder(slackTemplateBuilder: SlackTemplateBuilder): SlackApiRequester = SlackApiClientImpl(
        botToken = botToken, templateBuilder = slackTemplateBuilder
    )
}