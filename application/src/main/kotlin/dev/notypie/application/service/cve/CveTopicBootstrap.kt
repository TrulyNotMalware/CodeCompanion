package dev.notypie.application.service.cve

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.cve.CveTopicDefinition
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

private val log = KotlinLogging.logger {}

class CveTopicBootstrap(
    private val topics: List<AppConfig.Cve.TopicDefinition>,
    private val cveTopicRepository: CveTopicRepository,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun bootstrapTopics() {
        val written =
            topics
                .map { toDefinition(topic = it) }
                .count { cveTopicRepository.upsert(definition = it) }
        log.info { "CVE topic bootstrap finished: declared=${topics.size} written=$written" }
    }

    private fun toDefinition(topic: AppConfig.Cve.TopicDefinition): CveTopicDefinition {
        require(topic.key.isNotBlank()) { "CVE topic definition requires a non-blank key" }
        require(topic.displayName.isNotBlank()) { "CVE topic '${topic.key}' requires a non-blank display-name" }
        return CveTopicDefinition(
            topicKey = topic.key,
            displayName = topic.displayName,
            category = topic.category,
            sourceType = topic.sourceType,
            sourceConfig = topic.sourceConfig,
            deliveryMode = topic.deliveryMode,
            active = topic.active,
        )
    }
}
