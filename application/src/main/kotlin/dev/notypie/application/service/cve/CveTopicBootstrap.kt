package dev.notypie.application.service.cve

import dev.notypie.application.configurations.AppConfig
import dev.notypie.impl.cve.SourceAdapter
import dev.notypie.repository.cve.CveTopicDefinition
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

private val log = KotlinLogging.logger {}

class CveTopicBootstrap(
    private val topics: List<AppConfig.Cve.TopicDefinition>,
    private val cveTopicRepository: CveTopicRepository,
    private val adapters: List<SourceAdapter>,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun bootstrapTopics() {
        val definitions = topics.map { toDefinition(topic = it) }
        val caseClashes = definitions.groupBy { it.topicKey.lowercase() }.filterValues { it.size > 1 }.keys
        require(caseClashes.isEmpty()) { "CVE topic keys must be unique ignoring case: $caseClashes" }
        val written = definitions.count { cveTopicRepository.upsert(definition = it) }
        log.info { "CVE topic bootstrap finished: declared=${topics.size} written=$written" }
    }

    private fun toDefinition(topic: AppConfig.Cve.TopicDefinition): CveTopicDefinition {
        require(topic.key.isNotBlank()) { "CVE topic definition requires a non-blank key" }
        require(topic.displayName.isNotBlank()) { "CVE topic '${topic.key}' requires a non-blank display-name" }
        require(topic.displayName.codePointCount(0, topic.displayName.length) <= DISPLAY_NAME_MAX_LENGTH) {
            "CVE topic '${topic.key}' display-name exceeds $DISPLAY_NAME_MAX_LENGTH characters"
        }
        require(adapters.any { it.supports(sourceType = topic.sourceType) }) {
            "CVE topic '${topic.key}' declares sourceType ${topic.sourceType} but no source adapter supports it"
        }
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

    companion object {
        const val DISPLAY_NAME_MAX_LENGTH = 128
    }
}
