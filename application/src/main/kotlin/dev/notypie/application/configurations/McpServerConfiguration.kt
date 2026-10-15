package dev.notypie.application.configurations

import dev.notypie.application.mcp.DomainReadTools
import dev.notypie.application.mcp.McpToolGate
import dev.notypie.application.security.mcp.McpTurnTokenFilter
import dev.notypie.application.security.mcp.SCOPED_TURN_TOKEN_CONTEXT_KEY
import dev.notypie.application.security.mcp.ScopedTurnTokenCodec
import dev.notypie.application.service.agent.AgentUsageReportService
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.cve.query.CveLatestQueryService
import dev.notypie.application.service.cve.subscription.CveSubscriptionService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.standup.StandupRepository
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

private const val MIN_SIGNING_SECRET_LENGTH = 32
private const val SLACK_MCP_ENABLED = "slack.app.mcp.enabled"
private const val SPRING_AI_MCP_SERVER_ENABLED = "spring.ai.mcp.server.enabled"

private fun String?.switchedOn(matchIfMissing: Boolean): Boolean =
    this?.equals(other = "true", ignoreCase = true) ?: matchIfMissing

@Component
class McpSwitchesCheck : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val environment =
            beanFactory.getBean(ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME, Environment::class.java)
        val tokenPipeline = environment.getProperty(SLACK_MCP_ENABLED)
        val transport = environment.getProperty(SPRING_AI_MCP_SERVER_ENABLED)
        val tokenPipelineOn = tokenPipeline.switchedOn(matchIfMissing = false)
        val transportOn = transport.switchedOn(matchIfMissing = true)
        check(tokenPipelineOn == transportOn) {
            "$SLACK_MCP_ENABLED=${tokenPipeline ?: "unset"} (on: $tokenPipelineOn) and " +
                "$SPRING_AI_MCP_SERVER_ENABLED=${transport ?: "unset"} (on: $transportOn) must match: the first " +
                "switches the turn-token filter and tools, the second the /mcp transport, and each is on only " +
                "for the value true; set both, as MCP_ENABLED does in the prod profile"
        }
    }
}

@Configuration
@ConditionalOnProperty(prefix = "slack.app.mcp", name = ["enabled"], havingValue = "true")
class McpServerConfiguration {
    @Bean
    fun scopedTurnTokenCodec(appConfig: AppConfig, clock: Clock): ScopedTurnTokenCodec {
        require(appConfig.mcp.signingSecret.isNotBlank()) {
            "slack.app.mcp.signing-secret must be set when MCP is enabled"
        }
        require(appConfig.mcp.signingSecret.length >= MIN_SIGNING_SECRET_LENGTH) {
            "slack.app.mcp.signing-secret must be at least $MIN_SIGNING_SECRET_LENGTH characters when MCP is enabled"
        }
        return ScopedTurnTokenCodec(
            signingSecret = appConfig.mcp.signingSecret,
            tokenTtl = Duration.ofSeconds(appConfig.mcp.tokenTtlSeconds),
            clockSkew = Duration.ofSeconds(appConfig.mcp.clockSkewSeconds),
            clock = clock,
        )
    }

    @Bean
    fun mcpToolGate(
        commandRoleResolver: CommandRoleResolver,
        mcpToolCallHistoryRepository: McpToolCallHistoryRepository,
    ): McpToolGate =
        McpToolGate(
            commandRoleResolver = commandRoleResolver,
            mcpToolCallHistoryRepository = mcpToolCallHistoryRepository,
        )

    @Bean
    fun domainReadTools(
        mcpToolGate: McpToolGate,
        opsStatusService: OpsStatusService,
        roleManagementService: RoleManagementService,
        meetingRepository: MeetingRepository,
        standupRepository: StandupRepository,
        cveSubscriptionService: CveSubscriptionService,
        cveLatestQueryService: CveLatestQueryService,
        agentUsageReportService: AgentUsageReportService,
        appConfig: AppConfig,
        clock: Clock,
    ): DomainReadTools =
        DomainReadTools(
            mcpToolGate = mcpToolGate,
            opsStatusService = opsStatusService,
            roleManagementService = roleManagementService,
            meetingRepository = meetingRepository,
            standupRepository = standupRepository,
            cveSubscriptionService = cveSubscriptionService,
            cveLatestQueryService = cveLatestQueryService,
            agentUsageReportService = agentUsageReportService,
            appConfig = appConfig,
            clock = clock,
        )

    @Bean
    fun mcpTurnTokenFilterRegistration(
        scopedTurnTokenCodec: ScopedTurnTokenCodec,
        appConfig: AppConfig,
        properties: McpServerStreamableHttpProperties,
    ): FilterRegistrationBean<McpTurnTokenFilter> =
        FilterRegistrationBean(
            McpTurnTokenFilter(
                scopedTurnTokenCodec = scopedTurnTokenCodec,
                allowRemote = appConfig.mcp.allowRemote,
            ),
        ).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 20
            urlPatterns = listOf(properties.mcpEndpoint, "${properties.mcpEndpoint}/*")
        }

    @Bean
    fun webMvcStreamableServerTransportProvider(
        properties: McpServerStreamableHttpProperties,
        scopedTurnTokenCodec: ScopedTurnTokenCodec,
        jsonMapper: JsonMapper,
    ): WebMvcStreamableServerTransportProvider =
        WebMvcStreamableServerTransportProvider
            .builder()
            .jsonMapper(JacksonMcpJsonMapper(jsonMapper))
            .mcpEndpoint(properties.mcpEndpoint)
            .disallowDelete(properties.isDisallowDelete)
            .apply { properties.keepAliveInterval?.let { keepAliveInterval(it) } }
            .contextExtractor { request ->
                val token =
                    request
                        .headers()
                        .firstHeader("Authorization")
                        ?.takeIf { it.startsWith("Bearer ") }
                        ?.let { scopedTurnTokenCodec.verify(token = it.removePrefix("Bearer ")) }
                if (token == null) {
                    McpTransportContext.EMPTY
                } else {
                    McpTransportContext.create(mapOf(SCOPED_TURN_TOKEN_CONTEXT_KEY to token))
                }
            }.build()
}
