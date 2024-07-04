package dev.notypie.application.configurations

import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.impl.command.SlackModalResponseBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SlackResponseConfiguration {

    @Value("\${slack.api.token}")
    lateinit var botToken: String

    @Bean
    fun responseBuilder(): SlackRequestBuilder = SlackModalResponseBuilder(
        botToken = botToken
    )
}