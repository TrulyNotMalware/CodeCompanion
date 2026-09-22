package dev.notypie.application.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class SlackRequestVerificationConfiguration {
    // The only Clock bean in the context, so it reaches every constructor that declares a `clock: Clock`
    // parameter — a Kotlin default like `Clock.systemDefaultZone()` never applies when a bean exists. It must
    // therefore carry the JVM zone: the outbox schedulers and health probe turn it into LocalDateTime and
    // compare against DB timestamps written in that zone. Signature verification only reads epoch seconds.
    @Bean
    @ConditionalOnMissingBean
    fun slackRequestVerificationClock(): Clock = Clock.systemDefaultZone()

    @Bean
    @ConditionalOnMissingBean
    fun slackSignatureVerifier(clock: Clock): SlackSignatureVerifier = SlackSignatureVerifier(clock = clock)

    @Bean
    @ConditionalOnMissingBean
    fun slackRetryDeduplicator(): SlackRetryDeduplicator = InMemorySlackRetryDeduplicator()
}
