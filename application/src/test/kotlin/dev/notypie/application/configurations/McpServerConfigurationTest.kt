package dev.notypie.application.configurations

import dev.notypie.application.service.agent.AgentUsageReportService
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.cve.query.CveLatestQueryService
import dev.notypie.application.service.cve.subscription.CveSubscriptionService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.standup.StandupRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.function.Supplier

data class McpMapperProbe(
    val name: String,
    val count: Int,
)

class McpServerConfigurationTest :
    BehaviorSpec({
        fun startupFailureMessages(context: AssertableApplicationContext): String =
            generateSequence<Throwable>(checkNotNull(context.startupFailure)) { it.cause }
                .map { it.message.orEmpty() }
                .joinToString()

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
                .withBean(StandupRepository::class.java, { mockk(relaxed = true) })
                .withBean(CveSubscriptionService::class.java, { mockk(relaxed = true) })
                .withBean(CveLatestQueryService::class.java, { mockk(relaxed = true) })
                .withBean(AgentUsageReportService::class.java, { mockk(relaxed = true) })
                .withPropertyValues(
                    "slack.app.mcp.enabled=true",
                    "slack.app.mcp.signing-secret=mcp-test-secret-mcp-test-secret-",
                )

        given("the MCP switch check") {
            val switchesRunner = ApplicationContextRunner().withUserConfiguration(McpSwitchesCheck::class.java)

            `when`("both switches are on, the Spring AI one by its default") {
                then("the context starts") {
                    switchesRunner
                        .withPropertyValues("slack.app.mcp.enabled=true")
                        .run { context -> context.startupFailure shouldBe null }
                }
            }

            `when`("both switches are off") {
                then("the context starts") {
                    switchesRunner
                        .withPropertyValues("slack.app.mcp.enabled=false", "spring.ai.mcp.server.enabled=false")
                        .run { context -> context.startupFailure shouldBe null }
                }
            }

            `when`("neither switch is set, so only Spring AI's default turns its transport on") {
                then("startup fails showing both as unset") {
                    switchesRunner.run { context ->
                        val messages = startupFailureMessages(context = context)
                        messages shouldContain "slack.app.mcp.enabled=unset (on: false)"
                        messages shouldContain "spring.ai.mcp.server.enabled=unset (on: true)"
                    }
                }
            }

            `when`("ours is yes, which only true turns on, and Spring AI's is TRUE") {
                then("startup fails showing each configured value and how it evaluates") {
                    switchesRunner
                        .withPropertyValues("slack.app.mcp.enabled=yes", "spring.ai.mcp.server.enabled=TRUE")
                        .run { context ->
                            val messages = startupFailureMessages(context = context)
                            messages shouldContain "slack.app.mcp.enabled=yes (on: false)"
                            messages shouldContain "spring.ai.mcp.server.enabled=TRUE (on: true)"
                        }
                }
            }

            `when`("only the token pipeline is on") {
                then("startup fails showing both values") {
                    switchesRunner
                        .withPropertyValues("slack.app.mcp.enabled=true", "spring.ai.mcp.server.enabled=false")
                        .run { context ->
                            val messages = startupFailureMessages(context = context)
                            messages shouldContain "slack.app.mcp.enabled=true (on: true)"
                            messages shouldContain "spring.ai.mcp.server.enabled=false (on: false)"
                        }
                }
            }

            `when`("the switches diverge in a plain context whose first registered singleton fails when created") {
                val context =
                    AnnotationConfigApplicationContext().apply {
                        environment =
                            MockEnvironment()
                                .withProperty("slack.app.mcp.enabled", "true")
                                .withProperty("spring.ai.mcp.server.enabled", "false")
                        registerBean(
                            "singletonTripwire",
                            Any::class.java,
                            Supplier<Any> { error("a singleton was created before the MCP switch check") },
                        )
                        register(McpSwitchesCheck::class.java)
                    }

                then("the check fails before the ordinary singleton registered ahead of it is created") {
                    shouldThrow<IllegalStateException> { context.refresh() }.message shouldContain "must match"
                }
            }
        }

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

            `when`("the signing secret is one character shorter than the 32-character minimum") {
                val shortSecret = "mcp-test-secret-mcp-test-secret"

                then("startup fails naming the property and the minimum, never the value") {
                    contextRunner
                        .withPropertyValues("slack.app.mcp.signing-secret=$shortSecret")
                        .run { context ->
                            val messages = startupFailureMessages(context = context)
                            messages shouldContain "slack.app.mcp.signing-secret must be at least 32 characters"
                            messages shouldNotContain shortSecret
                        }
                }
            }
        }
    })
