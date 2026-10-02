package dev.notypie.application.configurations

import dev.notypie.application.security.mcp.ScopedTurnTokenCodec
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.application.service.agent.AgentTurn
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.SidecarAgentClient
import dev.notypie.repository.agent.AgentSessionRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executor

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
    @DependsOn("entityManagerFactory")
    fun agentTurnExecutor(): ThreadPoolTaskExecutor =
        AgentTurnExecutor().apply {
            val turns = appConfig.agent.turns
            corePoolSize = turns.maxConcurrent
            maxPoolSize = turns.maxConcurrent
            queueCapacity = turns.queueCapacity
            setThreadNamePrefix("agent-turn-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(turns.shutdownAwaitSeconds.toInt())
        }

    @Bean
    fun agentTurnIntake(
        @Qualifier("agentTurnExecutor") agentTurnExecutor: ThreadPoolTaskExecutor,
    ): AgentTurnIntake = AgentTurnIntake(executor = agentTurnExecutor)

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
        @Qualifier("agentTurnExecutor") agentTurnExecutor: Executor,
        scopedTurnTokenCodec: ObjectProvider<ScopedTurnTokenCodec>,
        clock: Clock,
    ): AgentConverseService =
        AgentConverseService(
            agentGateway = agentGateway,
            agentSessionRepository = agentSessionRepository,
            agentTurnHistoryRepository = agentTurnHistoryRepository,
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            meterRegistry = meterRegistry,
            transactionManager = transactionManager,
            turnExecutor = agentTurnExecutor,
            scopedTurnTokenCodec = scopedTurnTokenCodec.getIfAvailable(),
            clock = clock,
        )
}

// Spring's wait ends without shutdownNow, so a turn still queued then would vanish with the JVM, unanswered.
class AgentTurnExecutor : ThreadPoolTaskExecutor() {
    override fun shutdown() {
        super.shutdown()
        val unstarted = ArrayList<Runnable>()
        threadPoolExecutor.queue.drainTo(unstarted)
        unstarted.filterIsInstance<AgentTurn>().forEach { it.discard() }
    }
}

// From the start of the close a new turn is refused, so its mention gets the busy notice instead of queueing behind
// turns that may not finish before the pod is killed; turns already queued still run during the executor's wait.
class AgentTurnIntake(
    private val executor: ThreadPoolTaskExecutor,
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        executor.threadPoolExecutor.shutdown()
    }

    override fun isRunning(): Boolean = running
}
