package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveSummaryStatus
import java.time.LocalDateTime

data class CveEvent(
    val id: Long,
    val topicId: Long,
    val externalId: String,
    val title: String,
    val rawContent: String,
    val aiSummary: String?,
    val summaryStatus: CveSummaryStatus,
    val retryCount: Int,
)

data class TopicEventCount(
    val topicId: Long,
    val count: Long,
)

data class CveRecentEvent(
    val topicDisplayName: String,
    val title: String,
    val aiSummary: String?,
)

interface CveEventRepository {
    fun insertIgnore(
        topicId: Long,
        externalId: String,
        title: String,
        rawContent: String,
        publishedAt: LocalDateTime?,
    ): Int

    fun findClaimable(now: LocalDateTime, maxRetries: Int, limit: Int): List<CveEvent>

    fun claimForSummary(
        id: Long,
        token: String,
        now: LocalDateTime,
        maxRetries: Int,
    ): Int

    fun markDone(
        id: Long,
        token: String,
        summary: String,
        now: LocalDateTime,
    ): Int

    fun markFailed(id: Long, token: String, nextAttemptAt: LocalDateTime): Int

    fun releaseClaim(id: Long, token: String, nextAttemptAt: LocalDateTime): Int

    fun resetStuck(olderThan: LocalDateTime): Int

    fun countByStatus(status: CveSummaryStatus): Long

    fun countFailedRetryable(maxRetries: Int): Long

    fun countDeadLetter(maxRetries: Int): Long

    fun countEventsByTopic(topicIds: List<Long>): List<TopicEventCount>

    fun findRecentDoneEvents(topicIds: List<Long>, limit: Int): List<CveRecentEvent>

    fun resetDeadLetters(maxRetries: Int): Int

    fun resetDeadLetter(id: Long, maxRetries: Int): Int
}
