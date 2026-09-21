package dev.notypie.application.service.cve.ai

import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult

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
