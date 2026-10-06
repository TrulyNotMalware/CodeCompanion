package dev.notypie.application.configurations

import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

data class McpMapperProbe(
    val name: String,
    val count: Int,
)

class McpServerConfigurationTest :
    BehaviorSpec({
        val contextRunner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
                .withUserConfiguration(McpServerConfiguration::class.java, AppConfigBinding::class.java)
                .withBean(McpServerStreamableHttpProperties::class.java)
                .withBean(Clock::class.java, { Clock.systemUTC() })
                .withBean(CommandRoleResolver::class.java, { mockk(relaxed = true) })
                .withBean(McpToolCallHistoryRepository::class.java, { mockk(relaxed = true) })
                .withBean(OpsStatusService::class.java, { mockk(relaxed = true) })
                .withBean(RoleManagementService::class.java, { mockk(relaxed = true) })
                .withBean(MeetingRepository::class.java, { mockk(relaxed = true) })
                .withPropertyValues("slack.app.mcp.enabled=true", "slack.app.mcp.signing-secret=mcp-test-secret")

        given("the MCP server enabled") {
            `when`("the streamable HTTP transport is built") {
                then("its JSON mapper wraps Boot's JsonMapper, so the Kotlin module and spring.jackson.* apply") {
                    contextRunner.run { context ->
                        val transport = context.getBean(WebMvcStreamableServerTransportProvider::class.java)
                        val mcpMapper =
                            ReflectionTestUtils
                                .getField(
                                    transport,
                                    "jsonMapper",
                                ).shouldBeInstanceOf<JacksonMcpJsonMapper>()
                        mcpMapper.jsonMapper shouldBeSameInstanceAs context.getBean(JsonMapper::class.java)
                        mcpMapper.readValue("""{"name":"tool","count":2}""", McpMapperProbe::class.java) shouldBe
                            McpMapperProbe(name = "tool", count = 2)
                    }
                }
            }
        }
    })
