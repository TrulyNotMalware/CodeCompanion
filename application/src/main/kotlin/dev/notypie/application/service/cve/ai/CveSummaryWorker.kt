package dev.notypie.application.service.cve.ai

import dev.notypie.repository.cve.CveEvent
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import java.time.LocalDateTime
import java.util.UUID

private val log = KotlinLogging.logger {}

class CveSummaryWorker(
    private val cveEventRepository: CveEventRepository,
    private val cveTopicRepository: CveTopicRepository,
    private val aiSummarizer: AiSummarizer,
    private val batchSize: Int,
    private val maxRetries: Int,
    private val backoffMinutes: Long,
    private val stuckMinutes: Long,
) {
    @Scheduled(fixedDelay = 60_000)
    fun tick() {
        runCatching {
            cveEventRepository.resetStuck(olderThan = LocalDateTime.now().minusMinutes(stuckMinutes))
            cveEventRepository
                .findClaimable(now = LocalDateTime.now(), maxRetries = maxRetries, limit = batchSize)
                .forEach { event -> summarizeOne(event = event) }
        }.onFailure { ex ->
            log.error(ex) { "CVE summary worker tick failed" }
        }
    }

    private fun summarizeOne(event: CveEvent) {
        runCatching { dispatchOne(event = event) }
            .onFailure { ex -> log.error(ex) { "CVE summary handling failed for event=${event.id}" } }
    }

    private fun dispatchOne(event: CveEvent) {
        val token = UUID.randomUUID().toString()
        val claimed =
            cveEventRepository.claimForSummary(
                id = event.id,
                token = token,
                now = LocalDateTime.now(),
                maxRetries = maxRetries,
            )
        if (claimed == 0) return

        val summary =
            try {
                val topic =
                    requireNotNull(cveTopicRepository.findById(id = event.topicId)) {
                        "Topic ${event.topicId} for event ${event.id} no longer exists"
                    }
                aiSummarizer.summarize(
                    request =
                        SummaryRequest(
                            eventId = event.id,
                            topicDisplayName = topic.displayName,
                            category = topic.category,
                            eventTitle = event.title,
                            rawContent = event.rawContent,
                        ),
                )
            } catch (busy: AiSummarizerBusyException) {
                releaseForBackpressure(event = event, token = token, cause = busy)
                return
            } catch (ex: Exception) {
                recordFailure(event = event, token = token, cause = ex)
                return
            }

        val doneAt = LocalDateTime.now()
        if (cveEventRepository.markDone(id = event.id, token = token, summary = summary, now = doneAt) == 0) {
            log.warn {
                "CVE summary for event=${event.id} completed but the claim was lost; leaving row to its new owner"
            }
        }
    }

    private fun releaseForBackpressure(event: CveEvent, token: String, cause: AiSummarizerBusyException) {
        val nextAttemptAt = LocalDateTime.now().plusMinutes(BUSY_RETRY_DELAY_MINUTES)
        val released = cveEventRepository.releaseClaim(id = event.id, token = token, nextAttemptAt = nextAttemptAt)
        if (released == 0) {
            log.warn { "Busy release for event=${event.id} lost its claim; leaving row to its new owner" }
            return
        }
        log.info { "${cause.message}; released without consuming the retry budget, next attempt at $nextAttemptAt" }
    }

    private fun recordFailure(event: CveEvent, token: String, cause: Exception) {
        val attempt = event.retryCount + 1
        val nextAttemptAt = LocalDateTime.now().plusMinutes(backoffMinutes * attempt)
        if (cveEventRepository.markFailed(id = event.id, token = token, nextAttemptAt = nextAttemptAt) == 0) {
            log.warn { "Failure mark for event=${event.id} lost its claim; leaving row to its new owner" }
            return
        }
        if (attempt >= maxRetries) {
            log.error(
                cause,
            ) { "CVE summary permanently failed for event=${event.id} after $attempt attempts (dead-lettered)" }
        } else {
            log.error(cause) {
                "CVE summary failed for event=${event.id} (attempt $attempt/$maxRetries), next attempt at $nextAttemptAt"
            }
        }
    }

    companion object {
        private const val BUSY_RETRY_DELAY_MINUTES = 2L
    }
}
