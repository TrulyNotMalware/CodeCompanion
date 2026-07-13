package dev.notypie.application.service.cve.ai

import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult

/**
 * Summarizes over the existing agent lane (claude-sidecar). One-shot per event: a fresh sessionKey,
 * no resume sessionId, and no MCP scoped token — summarization needs no tools. A non-terminal
 * outcome (Busy) or a Failed frame throws so the worker records the failure and retries with backoff.
 */
class SidecarAiSummarizer(
    private val agentGateway: AgentGateway,
    private val promptBuilder: CveSummaryPromptBuilder,
) : AiSummarizer {
    override fun summarize(request: SummaryRequest): String {
        val result =
            agentGateway.converse(
                request =
                    AgentTurnRequest(
                        sessionKey = "cve:summary:${request.eventId}",
                        prompt = promptBuilder.build(request = request),
                    ),
            )
        return when (result) {
            is AgentTurnResult.Completed -> result.finalText
            is AgentTurnResult.Failed ->
                throw AiSummarizationException(
                    message =
                        "Sidecar summary failed for event=${request.eventId} " +
                            "code=${result.code}: ${result.message}",
                )
            AgentTurnResult.Busy ->
                throw AiSummarizerBusyException(message = "Sidecar busy (code=busy) for event=${request.eventId}")
        }
    }
}
