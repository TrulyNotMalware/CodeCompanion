package dev.notypie.application.configurations

import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestRequester
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RestClientConfiguration {
    companion object {
        const val SLACK_API_BASE_URL = "https://slack.com/api/"
    }

    @Bean
    fun restRequester(): RestRequester = RestClientRequester(baseUrl = SLACK_API_BASE_URL)
}