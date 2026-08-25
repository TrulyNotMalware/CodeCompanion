package dev.notypie.application.service.cve.collector

import dev.notypie.impl.cve.SourceAdapter
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/**
 * Polls each active topic on a fixed schedule and ingests new source events as PENDING; the M3
 * summary worker takes them from there. Per tick, each topic resolves its [SourceAdapter] by
 * source type, claims its collect window once ([CveCollectLedgerRepository] — a lost claim means
 * another instance owns the window, so the topic is skipped), fetches, and `insertIgnore`s every
 * raw event (idempotent on overlaps). Every topic runs under its own runCatching so one topic's
 * failure never aborts the rest. Feature-gated as a bean in CveConfiguration.
 */
class CveCollector(
    private val cveTopicRepository: CveTopicRepository,
    private val cveEventRepository: CveEventRepository,
    private val cveCollectLedgerRepository: CveCollectLedgerRepository,
    private val adapters: List<SourceAdapter>,
    private val windowMinutes: Long,
) {
    @Scheduled(fixedDelay = 300_000)
    fun tick() {
        val now = LocalDateTime.now()
        val windowStart = windowStart(now = now)
        cveTopicRepository.findActiveTopics().forEach { topic ->
            runCatching { collectTopic(topic = topic, windowStart = windowStart) }
                .onFailure { ex -> log.error(ex) { "CVE collection failed for topic=${topic.topicKey}" } }
        }
        runCatching { cveCollectLedgerRepository.deleteOlderThan(cutoff = now.minusDays(LEDGER_RETENTION_DAYS)) }
            .onFailure { ex -> log.error(ex) { "CVE collect ledger prune failed" } }
    }

    private fun collectTopic(topic: CveTopic, windowStart: LocalDateTime) {
        val adapter = adapters.firstOrNull { it.supports(sourceType = topic.sourceType) }
        if (adapter == null) {
            log.warn { "No source adapter for topic=${topic.topicKey} sourceType=${topic.sourceType}; skipping" }
            return
        }
        // Lost the claim: a concurrent instance owns this topic's window — skip without fetching.
        // Claiming before the fetch means a failed fetch burns its window by design: retrying
        // within the window would hammer rate-limited feeds, and dedup + source lookback self-heal.
        if (!cveCollectLedgerRepository.claimWindow(topicId = topic.id, windowStart = windowStart)) return

        val rawEvents = adapter.fetch(topic = topic)
        val inserted =
            rawEvents.sumOf { raw ->
                cveEventRepository.insertIgnore(
                    topicId = topic.id,
                    externalId = raw.externalId,
                    title = raw.title,
                    rawContent = raw.rawContent,
                    publishedAt = raw.publishedAt,
                )
            }
        log.info { "CVE collect topic=${topic.topicKey} fetched=${rawEvents.size} inserted=$inserted" }
    }

    // Tick time truncated to the collect-interval bucket, so every instance ticking in the same
    // interval derives the same window and races for one claim row.
    internal fun windowStart(now: LocalDateTime): LocalDateTime {
        val truncated = now.truncatedTo(ChronoUnit.MINUTES)
        val bucketMinute = (truncated.minute / windowMinutes * windowMinutes).toInt()
        return truncated.withMinute(bucketMinute)
    }

    companion object {
        const val LEDGER_RETENTION_DAYS = 7L
    }
}
