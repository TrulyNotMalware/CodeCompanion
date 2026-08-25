package dev.notypie.application.configurations

import dev.notypie.application.mcp.DomainReadTools
import dev.notypie.application.mcp.McpToolGate
import dev.notypie.application.security.mcp.McpTurnTokenFilter
import dev.notypie.application.security.mcp.SCOPED_TURN_TOKEN_CONTEXT_KEY
import dev.notypie.application.security.mcp.ScopedTurnTokenCodec
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * MCP server wiring for the agent lane's domain tools. Loaded only when
 * `slack.app.mcp.enabled=true`; `spring.ai.mcp.server.enabled` must be switched with it
 * (both ride the MCP_ENABLED env var) so the transport and the token pipeline stay in sync.
 */
@Configuration
@ConditionalOnProperty(prefix = "slack.app.mcp", name = ["enabled"], havingValue = "true")
class McpServerConfiguration {
    @Bean
    fun scopedTurnTokenCodec(appConfig: AppConfig): ScopedTurnTokenCodec {
        require(appConfig.mcp.signingSecret.isNotBlank()) {
            "slack.app.mcp.signing-secret must be set when MCP is enabled"
        }
        return ScopedTurnTokenCodec(
            signingSecret = appConfig.mcp.signingSecret,
            tokenTtl = Duration.ofSeconds(appConfig.mcp.tokenTtlSeconds),
            clockSkew = Duration.ofSeconds(appConfig.mcp.clockSkewSeconds),
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
    ): DomainReadTools =
        DomainReadTools(
            mcpToolGate = mcpToolGate,
            opsStatusService = opsStatusService,
            roleManagementService = roleManagementService,
            meetingRepository = meetingRepository,
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

    // Overrides the starter's provider (@ConditionalOnMissingBean) to attach the context
    // extractor: the verified turn token rides the McpTransportContext into every tool call.
    // A vanilla JsonMapper is deliberate — MCP protocol JSON needs none of the app's modules.
    @Bean
    fun webMvcStreamableServerTransportProvider(
        properties: McpServerStreamableHttpProperties,
        scopedTurnTokenCodec: ScopedTurnTokenCodec,
    ): WebMvcStreamableServerTransportProvider =
        WebMvcStreamableServerTransportProvider
            .builder()
            .jsonMapper(JacksonMcpJsonMapper(JsonMapper.builder().build()))
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
