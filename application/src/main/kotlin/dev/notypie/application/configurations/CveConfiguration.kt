package dev.notypie.application.configurations

import dev.notypie.application.service.cve.CveTopicBootstrap
import dev.notypie.application.service.cve.ai.AiSummarizer
import dev.notypie.application.service.cve.ai.CveSummaryPromptBuilder
import dev.notypie.application.service.cve.ai.CveSummaryWorker
import dev.notypie.application.service.cve.ai.NoopAiSummarizer
import dev.notypie.application.service.cve.ai.SidecarAiSummarizer
import dev.notypie.application.service.cve.collector.CveCollector
import dev.notypie.application.service.cve.notification.CveNotificationDispatcher
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.cve.GithubReleaseSourceAdapter
import dev.notypie.impl.cve.NvdCveSourceAdapter
import dev.notypie.impl.cve.SourceAdapter
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

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

    @Bean
    fun aiSummarizer(
        appConfig: AppConfig,
        agentGateway: AgentGateway,
        cveSummaryPromptBuilder: CveSummaryPromptBuilder,
    ): AiSummarizer =
        when (val provider = appConfig.ai.provider.lowercase()) {
            "noop" -> NoopAiSummarizer()
            "sidecar" -> {
                // A call outlasting stuckMinutes lets resetStuck reclaim the row mid-call, double-summarizing it.
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

    @Bean
    fun githubReleaseSourceAdapter(appConfig: AppConfig): SourceAdapter =
        GithubReleaseSourceAdapter(
            token = appConfig.cve.github.token,
            perPage = appConfig.cve.github.perPage,
            requestTimeout = Duration.ofSeconds(appConfig.cve.collector.requestTimeoutSeconds),
        )

    @Bean
    fun nvdCveSourceAdapter(appConfig: AppConfig): SourceAdapter {
        val lookbackMinutes = appConfig.cve.nvd.lookbackMinutes
        val windowMinutes = appConfig.cve.collector.windowMinutes
        require(lookbackMinutes >= windowMinutes * 2) {
            "slack.app.cve.nvd.lookback-minutes ($lookbackMinutes) must be >= twice " +
                "slack.app.cve.collector.window-minutes ($windowMinutes) to cover a burned window"
        }
        return NvdCveSourceAdapter(
            apiKey = appConfig.cve.nvd.apiKey,
            lookbackMinutes = lookbackMinutes,
            requestTimeout = Duration.ofSeconds(appConfig.cve.collector.requestTimeoutSeconds),
        )
    }

    @Bean
    fun cveCollector(
        appConfig: AppConfig,
        cveTopicRepository: CveTopicRepository,
        cveEventRepository: CveEventRepository,
        cveCollectLedgerRepository: CveCollectLedgerRepository,
        sourceAdapters: List<SourceAdapter>,
    ): CveCollector {
        // windowMinutes must divide 60 evenly, or bucket boundaries drift across the hour (0 throws).
        val windowMinutes = appConfig.cve.collector.windowMinutes
        require(windowMinutes in 1L..60L && 60L % windowMinutes == 0L) {
            "slack.app.cve.collector.window-minutes ($windowMinutes) must be a divisor of 60 in 1..60"
        }
        return CveCollector(
            cveTopicRepository = cveTopicRepository,
            cveEventRepository = cveEventRepository,
            cveCollectLedgerRepository = cveCollectLedgerRepository,
            adapters = sourceAdapters,
            windowMinutes = windowMinutes,
        )
    }

    @Bean
    fun cveNotificationDispatcher(
        appConfig: AppConfig,
        cveDeliveryRepository: CveDeliveryRepository,
        outboxRepository: MessageOutboxRepository,
        outboundMessagePort: OutboundMessagePort,
        transactionManager: PlatformTransactionManager,
        clock: Clock,
    ): CveNotificationDispatcher {
        val notification = appConfig.cve.notification
        val digestSendAt =
            runCatching { LocalTime.parse(notification.digestSendAt) }
                .getOrElse {
                    error("slack.app.cve.notification.digest-send-at ('${notification.digestSendAt}') must be HH:mm")
                }
        val digestZone =
            runCatching { ZoneId.of(notification.digestTimezone) }
                .getOrElse {
                    error(
                        "slack.app.cve.notification.digest-timezone ('${notification.digestTimezone}') " +
                            "must be a valid zone id",
                    )
                }
        require(notification.batchSize > 0) {
            "slack.app.cve.notification.batch-size (${notification.batchSize}) must be > 0"
        }
        require(notification.digestSummaryMaxLength > 0) {
            "slack.app.cve.notification.digest-summary-max-length " +
                "(${notification.digestSummaryMaxLength}) must be > 0"
        }
        require(notification.deliveryHorizonDays > 0) {
            "slack.app.cve.notification.delivery-horizon-days " +
                "(${notification.deliveryHorizonDays}) must be > 0"
        }
        return CveNotificationDispatcher(
            cveDeliveryRepository = cveDeliveryRepository,
            outboxRepository = outboxRepository,
            outboundMessagePort = outboundMessagePort,
            transactionManager = transactionManager,
            batchSize = notification.batchSize,
            digestSendAt = digestSendAt,
            digestZone = digestZone,
            digestSummaryMaxLength = notification.digestSummaryMaxLength,
            deliveryHorizonDays = notification.deliveryHorizonDays,
            clock = clock,
        )
    }
}
