package dev.notypie.application.configurations

import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.SidecarAgentClient
import dev.notypie.repository.agent.AgentSessionRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

/**
 * Wires the AI-agent lane: the sidecar HTTP+SSE adapter and the async converse listener.
 * [AgentConverseService] is an explicit `@Bean` (not component-scanned) because the class-level
 * `@Async` relies on a CGLIB subclass proxy, and co-locating the wiring here keeps the
 * bean-ordering explicit.
 */
@Configuration
class AgentConfiguration(
    private val appConfig: AppConfig,
) {
    @Bean
    @ConditionalOnMissingBean(AgentGateway::class)
    fun agentGateway(): AgentGateway =
        SidecarAgentClient(
            baseUrl = appConfig.agent.sidecar.baseUrl,
            bearerSecret = appConfig.agent.sidecar.bearerSecret,
            requestTimeout = Duration.ofSeconds(appConfig.agent.sidecar.requestTimeoutSeconds),
        )

    @Bean
    @ConditionalOnMissingBean(AgentConverseService::class)
    fun agentConverseService(
        agentGateway: AgentGateway,
        agentSessionRepository: AgentSessionRepository,
        agentTurnHistoryRepository: AgentTurnHistoryRepository,
        outboundStager: OutboundMessageStager,
        eventPublisher: EventPublisher,
        meterRegistry: MeterRegistry,
        transactionManager: PlatformTransactionManager,
    ): AgentConverseService =
        AgentConverseService(
            agentGateway = agentGateway,
            agentSessionRepository = agentSessionRepository,
            agentTurnHistoryRepository = agentTurnHistoryRepository,
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            meterRegistry = meterRegistry,
            transactionManager = transactionManager,
        )
}
