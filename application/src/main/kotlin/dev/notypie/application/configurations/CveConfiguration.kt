package dev.notypie.application.configurations

import dev.notypie.application.service.cve.CveTopicBootstrap
import dev.notypie.repository.cve.CveTopicRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * CVE-Bot wiring, off by default: without `slack.app.cve.enabled=true` no bean here
 * exists and the feature leaves zero footprint.
 */
@Configuration
@ConditionalOnProperty(prefix = "slack.app.cve", name = ["enabled"], havingValue = "true")
class CveConfiguration {
    @Bean
    fun cveTopicBootstrap(appConfig: AppConfig, cveTopicRepository: CveTopicRepository): CveTopicBootstrap =
        CveTopicBootstrap(
            topics = appConfig.cve.topics,
            cveTopicRepository = cveTopicRepository,
        )
}
