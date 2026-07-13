package dev.notypie.application.service.cve.ai

/**
 * Default summarizer with zero external calls: echoes the title and a truncated slice of the raw
 * content so the summarize-once pipeline stays fully functional (and deterministic) without any AI
 * backend wired in.
 */
class NoopAiSummarizer : AiSummarizer {
    override fun summarize(request: SummaryRequest): String {
        val body = request.rawContent.take(MAX_CONTENT_CHARS)
        return if (body.isBlank()) request.eventTitle else "${request.eventTitle}\n\n$body"
    }

    companion object {
        const val MAX_CONTENT_CHARS = 1500
    }
}
