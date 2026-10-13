package dev.notypie.application

import dev.notypie.CodeCompanion
import dev.notypie.application.configurations.AgentTurnIntake
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.domain.command.createAgentConverseRequestEvent
import dev.notypie.domain.command.createAgentQuestionMessage
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.repository.agent.schema.AgentAskTurnStatus
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessageDeliveredEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
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
        given("the application with its only turn slot busy and turns queued or arriving as the context closes") {
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
            val outboxRepository = context.getBean(MessageOutboxRepository::class.java)

            fun deliveredQuestion(): OutboxMessage =
                outboxRepository.save(
                    CodecOutboundMessagePort()
                        .toRow(message = createAgentQuestionMessage(), basicInfo = createCommandBasicInfo())
                        .apply { updateMessageStatus(status = MessageStatus.SUCCESS) },
                )

            fun announce(question: OutboxMessage) =
                context.publishEvent(
                    MessageDeliveredEvent(
                        eventId = UUID.fromString(question.eventId),
                        commandDetailType = CommandDetailType.AGENT_ASK_QUESTION,
                        messageTs = TEST_MESSAGE_TS,
                    ),
                )

            fun column(sql: String, argument: String): List<String> =
                DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
                    connection
                        .prepareStatement(sql)
                        .apply { setString(1, argument) }
                        .executeQuery()
                        .use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                }

            fun outboxRowIds(question: OutboxMessage): List<String> =
                column(
                    sql = "SELECT event_id FROM outbox_message WHERE idempotency_key = ?",
                    argument = question.idempotencyKey,
                )

            fun turnOf(question: OutboxMessage): List<String> =
                column(
                    sql = "SELECT CONCAT(status, ':', attempt_count) FROM agent_ask_turn WHERE event_id = ?",
                    argument = question.eventId,
                )

            val asked = deliveredQuestion()
            val askedLate = deliveredQuestion()

            `when`("the context closes before the queued turns can start") {
                service.handleAgentConverse(event = createAgentConverseRequestEvent())
                service.handleAgentConverse(event = queued)
                announce(question = asked)
                context.getBean(AgentTurnIntake::class.java).stop()
                announce(question = askedLate)
                context.close()
                release.countDown()
                val mentionPayloads =
                    column(
                        sql = "SELECT payload FROM outbox_message WHERE idempotency_key = ?",
                        argument = queued.idempotencyKey.toString(),
                    )

                then("the mention's overload notice is in the outbox, written while the context was closing") {
                    mentionPayloads shouldHaveSize 1
                    mentionPayloads.single() shouldContain AgentConverseService.OVERLOADED_MESSAGE
                }

                then("the queued /ask turn gets no notice and stays PENDING, never begun, for the next start") {
                    outboxRowIds(question = asked) shouldContainExactly listOf(asked.eventId)
                    turnOf(question = asked) shouldContainExactly listOf("${AgentAskTurnStatus.PENDING}:0")
                }

                then("an /ask question delivered after the executor stopped taking turns is left the same way") {
                    outboxRowIds(question = askedLate) shouldContainExactly listOf(askedLate.eventId)
                    turnOf(question = askedLate) shouldContainExactly listOf("${AgentAskTurnStatus.PENDING}:0")
                }
            }
        }
    })
