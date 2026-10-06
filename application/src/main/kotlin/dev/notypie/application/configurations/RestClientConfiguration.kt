package dev.notypie.application.configurations

import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestClientRequester.Companion.SLACK_API_BASE_URL
import dev.notypie.impl.command.RestRequester
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfiguration {
    @Bean
    fun restRequester(restClientBuilder: RestClient.Builder): RestRequester =
        RestClientRequester(baseUrl = SLACK_API_BASE_URL, restClientBuilder = restClientBuilder)
}
