package dev.notypie.application.service.cve.ai

import dev.notypie.domain.common.takeSurrogateSafe

class NoopAiSummarizer : AiSummarizer {
    override fun summarize(request: SummaryRequest): String {
        val body = request.rawContent.takeSurrogateSafe(maxLength = MAX_CONTENT_CHARS)
        return if (body.isBlank()) request.eventTitle else "${request.eventTitle}\n\n$body"
    }

    companion object {
        const val MAX_CONTENT_CHARS = 1500
    }
}
