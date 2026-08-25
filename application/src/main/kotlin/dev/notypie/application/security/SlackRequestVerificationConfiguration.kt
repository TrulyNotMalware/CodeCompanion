package dev.notypie.application.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class SlackRequestVerificationConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun slackRequestVerificationClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean
    fun slackSignatureVerifier(clock: Clock): SlackSignatureVerifier = SlackSignatureVerifier(clock = clock)

    @Bean
    @ConditionalOnMissingBean
    fun slackRetryDeduplicator(): SlackRetryDeduplicator = InMemorySlackRetryDeduplicator()
}
