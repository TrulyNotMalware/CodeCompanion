package dev.notypie.application

import dev.notypie.CodeCompanion
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.domain.command.createAgentConverseRequestEvent
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentTurnShutdownContextTest :
    BehaviorSpec({
        given("the application with its only turn slot busy and one turn queued when the context closes") {
            val databaseUrl = "jdbc:h2:mem:agent-shutdown-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            val release = CountDownLatch(1)
            val gateway =
                mockk<AgentGateway> {
                    every { converse(request = any()) } answers {
                        release.await(30L, TimeUnit.SECONDS)
                        AgentTurnResult.Completed(sessionId = null, finalText = "late answer")
                    }
                }
            val context =
                SpringApplicationBuilder(CodeCompanion::class.java)
                    .web(WebApplicationType.NONE)
                    .initializers(
                        ApplicationContextInitializer<ConfigurableApplicationContext> {
                            it.beanFactory.registerSingleton("agentGateway", gateway)
                        },
                    ).properties(
                        "spring.datasource.url=$databaseUrl",
                        "spring.jpa.hibernate.ddl-auto=create",
                        "slack.app.api.token=xoxb-shutdown-test",
                        "slack.app.api.signing-secret=shutdown-test-signing-secret",
                        "slack.app.agent.turns.max-concurrent=1",
                        "slack.app.agent.turns.shutdown-await-seconds=1",
                    ).run()
            val service = context.getBean(AgentConverseService::class.java)
            val queued = createAgentConverseRequestEvent()

            `when`("the context closes before the queued turn can start") {
                service.handleAgentConverse(event = createAgentConverseRequestEvent())
                service.handleAgentConverse(event = queued)
                context.close()
                release.countDown()
                val payloads =
                    DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
                        connection
                            .prepareStatement("SELECT payload FROM outbox_message WHERE idempotency_key = ?")
                            .apply { setString(1, queued.idempotencyKey.toString()) }
                            .executeQuery()
                            .use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                    }

                then("its overload notice is in the outbox, written while the context was closing") {
                    payloads shouldHaveSize 1
                    payloads.single() shouldContain AgentConverseService.OVERLOADED_MESSAGE
                }
            }
        }
    })
