package dev.notypie.application.configurations

import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.impl.command.SlackModalResponseBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SlackResponseConfiguration {

    @Bean
    fun responseBuilder(): SlackResponseBuilder = SlackModalResponseBuilder()
}