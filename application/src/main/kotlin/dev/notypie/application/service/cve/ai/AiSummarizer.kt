package dev.notypie.application.service.cve.ai

import dev.notypie.repository.cve.schema.CveTopicCategory

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

class AiSummarizerBusyException(
    message: String,
) : AiSummarizationException(message)
