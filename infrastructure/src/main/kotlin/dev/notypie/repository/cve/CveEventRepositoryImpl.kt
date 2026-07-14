package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveEventSchema
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

open class CveEventRepositoryImpl(
    private val jpaCveEventRepository: JpaCveEventRepository,
) : CveEventRepository {
    @Transactional
    override fun insertIgnore(
        topicId: Long,
        externalId: String,
        title: String,
        rawContent: String,
        publishedAt: LocalDateTime?,
    ): Int =
        jpaCveEventRepository.insertIgnore(
            topicId = topicId,
            externalId = externalId,
            title = title.take(TITLE_MAX_LENGTH),
            rawContent = rawContent.take(RAW_CONTENT_MAX_LENGTH),
            publishedAt = publishedAt,
        )

    override fun findClaimable(now: LocalDateTime, maxRetries: Int, limit: Int): List<CveEvent> =
        jpaCveEventRepository
            .findClaimable(now = now, maxRetries = maxRetries, pageable = PageRequest.of(0, limit))
            .map { toRecord(schema = it) }

    @Transactional
    override fun claimForSummary(id: Long, token: String, now: LocalDateTime): Int =
        jpaCveEventRepository.claimForSummary(id = id, token = token, now = now)

    @Transactional
    override fun markDone(id: Long, token: String, summary: String): Int =
        jpaCveEventRepository.markDone(id = id, token = token, summary = summary)

    @Transactional
    override fun markFailed(id: Long, token: String, nextAttemptAt: LocalDateTime): Int =
        jpaCveEventRepository.markFailed(id = id, token = token, nextAttemptAt = nextAttemptAt)

    @Transactional
    override fun releaseClaim(id: Long, token: String, nextAttemptAt: LocalDateTime): Int =
        jpaCveEventRepository.releaseClaim(id = id, token = token, nextAttemptAt = nextAttemptAt)

    @Transactional
    override fun resetStuck(olderThan: LocalDateTime): Int = jpaCveEventRepository.resetStuck(olderThan = olderThan)

    private fun toRecord(schema: CveEventSchema): CveEvent =
        CveEvent(
            id = schema.id,
            topicId = schema.topicId,
            externalId = schema.externalId,
            title = schema.title,
            rawContent = schema.rawContent,
            aiSummary = schema.aiSummary,
            summaryStatus = schema.summaryStatus,
            retryCount = schema.retryCount,
        )

    companion object {
        // Match the cve_event column limits so an over-long feed payload never overflows the insert.
        const val TITLE_MAX_LENGTH = 512
        const val RAW_CONTENT_MAX_LENGTH = 60_000
    }
}
