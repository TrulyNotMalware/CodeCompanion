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
        val definitions = topics.map { toDefinition(topic = it) }
        // /latest matches keys ignoring case, and MariaDB's _ci collation would reject the pair on the UNIQUE key.
        val caseClashes = definitions.groupBy { it.topicKey.lowercase() }.filterValues { it.size > 1 }.keys
        require(caseClashes.isEmpty()) { "CVE topic keys must be unique ignoring case: $caseClashes" }
        val declaredActive = definitions.count { it.active }
        require(declaredActive <= MAX_ACTIVE_TOPICS) {
            "CVE topics declare $declaredActive active topics; the subscribe modal lists at most $MAX_ACTIVE_TOPICS"
        }
        val written = definitions.count { cveTopicRepository.upsert(definition = it) }
        log.info { "CVE topic bootstrap finished: declared=${topics.size} written=$written" }
        // yaml only seeds `active` on insert, so rows toggled in chat or absent from yaml can still push the live
        // count over the limit. That is runtime state an admin fixes with `cve topic deactivate`, so it only logs.
        val liveActive = cveTopicRepository.countActive()
        if (liveActive > MAX_ACTIVE_TOPICS) {
            log.error {
                "cve_topic has $liveActive active topics; the subscribe modal lists at most $MAX_ACTIVE_TOPICS"
            }
        }
    }

    // Limits: the key is the subscribe modal's option value (150 chars in Slack) but cve_topic.topic_key is
    // VARCHAR(64), the tighter bound. The display name is only bounded by cve_topic.display_name VARCHAR(128):
    // the templates cut an option label to Slack's 75 characters, so a longer name renders and must not fail
    // the boot. A select holds at most 100 options. Failing the boot beats a modal that no user can open.
    private fun toDefinition(topic: AppConfig.Cve.TopicDefinition): CveTopicDefinition {
        require(topic.key.isNotBlank()) { "CVE topic definition requires a non-blank key" }
        require(topic.key.length <= KEY_MAX_LENGTH) {
            "CVE topic key '${topic.key}' exceeds $KEY_MAX_LENGTH characters"
        }
        require(topic.displayName.isNotBlank()) { "CVE topic '${topic.key}' requires a non-blank display-name" }
        // Counted in code points, as MariaDB counts VARCHAR(128) characters under utf8mb4: an emoji is one character
        // there but two UTF-16 units here.
        require(topic.displayName.codePointCount(0, topic.displayName.length) <= DISPLAY_NAME_MAX_LENGTH) {
            "CVE topic '${topic.key}' display-name exceeds $DISPLAY_NAME_MAX_LENGTH characters"
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
        const val KEY_MAX_LENGTH = 64
        const val DISPLAY_NAME_MAX_LENGTH = 128
        const val MAX_ACTIVE_TOPICS = 100
    }
}
