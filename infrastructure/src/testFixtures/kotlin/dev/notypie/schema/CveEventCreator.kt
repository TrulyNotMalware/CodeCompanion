package dev.notypie.schema

import dev.notypie.impl.cve.RawSourceEvent
import dev.notypie.repository.cve.CveEvent
import dev.notypie.repository.cve.schema.CveEventSchema
import dev.notypie.repository.cve.schema.CveSummaryStatus
import java.time.LocalDateTime

fun createCveEvent(
    id: Long = 1L,
    topicId: Long = 1L,
    externalId: String = "CVE-2026-0001",
    title: String = "Sample advisory",
    rawContent: String = "Raw advisory content.",
    aiSummary: String? = null,
    summaryStatus: CveSummaryStatus = CveSummaryStatus.PENDING,
    retryCount: Int = 0,
): CveEvent =
    CveEvent(
        id = id,
        topicId = topicId,
        externalId = externalId,
        title = title,
        rawContent = rawContent,
        aiSummary = aiSummary,
        summaryStatus = summaryStatus,
        retryCount = retryCount,
    )

fun createCveEventSchema(
    id: Long = 0L,
    topicId: Long = 1L,
    externalId: String = "CVE-2026-0001",
    title: String = "Sample advisory",
    rawContent: String = "Raw advisory content.",
    aiSummary: String? = null,
    summaryStatus: CveSummaryStatus = CveSummaryStatus.PENDING,
    claimToken: String? = null,
    retryCount: Int = 0,
    nextAttemptAt: LocalDateTime? = null,
    publishedAt: LocalDateTime? = null,
): CveEventSchema =
    CveEventSchema(
        id = id,
        topicId = topicId,
        externalId = externalId,
        title = title,
        rawContent = rawContent,
        aiSummary = aiSummary,
        summaryStatus = summaryStatus,
        claimToken = claimToken,
        retryCount = retryCount,
        nextAttemptAt = nextAttemptAt,
        publishedAt = publishedAt,
    )

fun createRawSourceEvent(
    externalId: String = "R-0001",
    title: String = "Sample release",
    rawContent: String = "Raw release notes.",
    publishedAt: LocalDateTime? = null,
): RawSourceEvent =
    RawSourceEvent(
        externalId = externalId,
        title = title,
        rawContent = rawContent,
        publishedAt = publishedAt,
    )
