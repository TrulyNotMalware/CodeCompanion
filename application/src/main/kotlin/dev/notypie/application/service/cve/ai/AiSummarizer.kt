package dev.notypie.application.service.cve.ai

import dev.notypie.repository.cve.schema.CveTopicCategory

/**
 * Produces the single AI summary for a CVE event. Implementations are pure request/response;
 * throwing signals failure and the worker records it and retries with backoff. The summary is
 * produced exactly once per event, so implementations must not assume idempotent retries.
 */
interface AiSummarizer {
    fun summarize(request: SummaryRequest): String
}

data class SummaryRequest(
    val eventId: Long,
    val topicDisplayName: String,
    val category: CveTopicCategory,
    val eventTitle: String,
    val rawContent: String,
)

open class AiSummarizationException(
    message: String,
) : RuntimeException(message)

/** Backpressure, not an error: the worker releases the claim without consuming the retry budget. */
class AiSummarizerBusyException(
    message: String,
) : AiSummarizationException(message)
