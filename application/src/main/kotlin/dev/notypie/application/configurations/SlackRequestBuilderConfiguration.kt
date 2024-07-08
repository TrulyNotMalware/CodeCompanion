package dev.notypie.application.configurations

import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.impl.command.SlackRequestBuilderImpl
import dev.notypie.slack.ModalTemplateBuilder
import dev.notypie.slack.SlackTemplateBuilder
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
    fun slackRequestBuilder(slackTemplateBuilder: SlackTemplateBuilder): SlackRequestBuilder = SlackRequestBuilderImpl(
        botToken = botToken, templateBuilder = slackTemplateBuilder
    )
}