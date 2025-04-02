package dev.notypie.application.configurations

import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestClientRequester.Companion.SLACK_API_BASE_URL
import dev.notypie.impl.command.RestRequester
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RestClientConfiguration {

    @Bean
    fun restRequester(): RestRequester = RestClientRequester(baseUrl = SLACK_API_BASE_URL)
}