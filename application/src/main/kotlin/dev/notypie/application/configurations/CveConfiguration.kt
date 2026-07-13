package dev.notypie.application.configurations

import dev.notypie.application.service.cve.CveTopicBootstrap
import dev.notypie.application.service.cve.ai.AiSummarizer
import dev.notypie.application.service.cve.ai.CveSummaryPromptBuilder
import dev.notypie.application.service.cve.ai.CveSummaryWorker
import dev.notypie.application.service.cve.ai.NoopAiSummarizer
import dev.notypie.application.service.cve.ai.SidecarAiSummarizer
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * CVE-Bot wiring, off by default: without `slack.app.cve.enabled=true` no bean here
 * exists and the feature leaves zero footprint.
 */
@Configuration
@ConditionalOnProperty(prefix = "slack.app.cve", name = ["enabled"], havingValue = "true")
class CveConfiguration {
    @Bean
    fun cveTopicBootstrap(appConfig: AppConfig, cveTopicRepository: CveTopicRepository): CveTopicBootstrap =
        CveTopicBootstrap(
            topics = appConfig.cve.topics,
            cveTopicRepository = cveTopicRepository,
        )

    @Bean
    fun cveSummaryPromptBuilder(): CveSummaryPromptBuilder = CveSummaryPromptBuilder()

    /**
     * Selects the summarizer from `slack.app.ai.provider`. `sidecar` reuses the agent lane's
     * gateway (always wired by AgentConfiguration); an unknown value fails the boot rather than
     * silently degrading to noop.
     */
    @Bean
    fun aiSummarizer(
        appConfig: AppConfig,
        agentGateway: AgentGateway,
        cveSummaryPromptBuilder: CveSummaryPromptBuilder,
    ): AiSummarizer =
        when (val provider = appConfig.ai.provider.lowercase()) {
            "noop" -> NoopAiSummarizer()
            "sidecar" -> {
                // A live summarize call must never outlast the stuck threshold, or resetStuck can
                // reclaim the row mid-call and a second instance double-summarizes it.
                require(appConfig.ai.stuckMinutes * 60 > appConfig.agent.sidecar.requestTimeoutSeconds) {
                    "slack.app.ai.stuck-minutes (${appConfig.ai.stuckMinutes}m) must exceed " +
                        "slack.app.agent.sidecar.request-timeout-seconds " +
                        "(${appConfig.agent.sidecar.requestTimeoutSeconds}s)"
                }
                SidecarAiSummarizer(
                    agentGateway = agentGateway,
                    promptBuilder = cveSummaryPromptBuilder,
                )
            }
            else -> error("Unknown slack.app.ai.provider '$provider' (expected: noop | sidecar)")
        }

    @Bean
    fun cveSummaryWorker(
        appConfig: AppConfig,
        cveEventRepository: CveEventRepository,
        cveTopicRepository: CveTopicRepository,
        aiSummarizer: AiSummarizer,
    ): CveSummaryWorker =
        CveSummaryWorker(
            cveEventRepository = cveEventRepository,
            cveTopicRepository = cveTopicRepository,
            aiSummarizer = aiSummarizer,
            batchSize = appConfig.ai.batchSize,
            maxRetries = appConfig.ai.maxRetries,
            backoffMinutes = appConfig.ai.backoffMinutes,
            stuckMinutes = appConfig.ai.stuckMinutes,
        )
}
