package dev.notypie.application

import com.ninjasquad.springmockk.MockkBean
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariDataSource
import dev.notypie.application.security.SlackRetryDeduplicator
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.meeting.DailyAgendaSchedulingService
import dev.notypie.application.service.meeting.MeetingReminderSchedulingService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.application.service.standup.StandupAnswerService
import dev.notypie.application.service.standup.StandupSchedulingService
import dev.notypie.domain.command.createAgentConverseRequestEvent
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.ViewOpenDeferral
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.impl.command.dto.createUserProfileResponseJson
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload
import dev.notypie.impl.command.event.createOpenViewEvent
import dev.notypie.repository.meeting.MeetingRepositoryImpl
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetSocketAddress
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:application-smoke;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.threads.virtual.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=20",
        "slack.app.api.token=xoxb-smoke-test",
        "slack.app.api.signing-secret=smoke-test-signing-secret",
    ],
)
@DirtiesContext
@ApplyExtension(extensions = [SpringExtension::class])
class ApplicationContextSmokeTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val eventPublisher: ApplicationEventPublisher,
        private val transactionTemplate: TransactionTemplate,
        private val outboxRepository: MessageOutboxRepository,
        @MockkBean(relaxed = true) private val messageDispatcher: MessageDispatcher,
        @MockkBean private val agentGateway: AgentGateway,
    ) : BehaviorSpec({
            given("the application context with polling relay and in-process events") {
                `when`("it starts") {
                    then("the Hikari pool takes spring.datasource.hikari") {
                        context.getBean(HikariDataSource::class.java).maximumPoolSize shouldBe 20
                    }

                    then("@Scheduled jobs run on a 4-thread ThreadPoolTaskScheduler") {
                        val scheduler = context.getBean(TaskScheduler::class.java)
                        scheduler.shouldBeInstanceOf<ThreadPoolTaskScheduler>()
                        scheduler.scheduledThreadPoolExecutor.corePoolSize shouldBe 4
                    }

                    then("the relay is wired to the dedicated relayTaskExecutor") {
                        val relay =
                            AopTestUtils.getUltimateTargetObject<SlackMessageRelayServiceImpl>(
                                context.getBean(SlackMessageRelayServiceImpl::class.java),
                            )
                        ReflectionTestUtils.getField(relay, "relayTaskExecutor") shouldBeSameInstanceAs
                            context.getBean("relayTaskExecutor")
                    }

                    then("one Clock bean exists, in the JVM zone") {
                        val clocks = context.getBeansOfType(Clock::class.java).values
                        clocks shouldHaveSize 1
                        clocks.single().zone shouldBe ZoneId.systemDefault()
                    }

                    then("every clock-dependent bean, factory-built ones included, holds that Clock bean") {
                        val clock = context.getBean(Clock::class.java)
                        listOf(
                            SlackRetryDeduplicator::class.java,
                            CommandRoleResolver::class.java,
                            AgentConverseService::class.java,
                            MeetingReminderSchedulingService::class.java,
                            DailyAgendaSchedulingService::class.java,
                            StandupSchedulingService::class.java,
                            StandupAnswerService::class.java,
                            OpsStatusService::class.java,
                            MeetingRepositoryImpl::class.java,
                        ).forEach { type ->
                            val bean = AopTestUtils.getUltimateTargetObject<Any>(context.getBean(type))
                            ReflectionTestUtils.getField(bean, "clock") shouldBeSameInstanceAs clock
                        }
                    }

                    then(
                        "the Slack REST requester uses Boot's RestClient.Builder: calls are observed, profiles decode",
                    ) {
                        val server =
                            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                                createContext("/") { exchange ->
                                    val body = createUserProfileResponseJson(displayName = "smoke-user").toByteArray()
                                    exchange.responseHeaders.add("Content-Type", "application/json")
                                    exchange.sendResponseHeaders(200, body.size.toLong())
                                    exchange.responseBody.use { it.write(body) }
                                }
                                start()
                            }
                        val profile =
                            try {
                                context.getBean(RestRequester::class.java).safeGet(
                                    uri = "http://127.0.0.1:${server.address.port}/users.profile.get",
                                    authorizationHeader = null,
                                    responseType = SlackUserProfileDto::class.java,
                                    uriVariables = emptyMap(),
                                )
                            } finally {
                                server.stop(0)
                            }
                        profile
                            .getOrThrow()
                            .body
                            ?.profile
                            ?.displayName shouldBe "smoke-user"
                        context
                            .getBean(
                                MeterRegistry::class.java,
                            ).find("http.client.requests")
                            .timer()
                            .shouldNotBeNull()
                    }

                    then("open-in-view is off, so lazy loads cannot hide outside a transaction") {
                        context.environment.getProperty("spring.jpa.open-in-view") shouldBe "false"
                    }

                    then("the Prometheus registry scrapes the outbox gauges") {
                        val scrape = context.getBean(PrometheusMeterRegistry::class.java).scrape()
                        scrape shouldContain "outbox_messages{status=\"pending\"} 0.0"
                        scrape shouldContain "outbox_pending_oldest_age_seconds 0.0"
                        scrape shouldContain "outbox_in_progress_oldest_claim_age_seconds 0.0"
                        scrape shouldContain "outbox_retrying_messages 0.0"
                    }
                }
            }

            given("a views.open published through Spring's event multicaster inside a deferral boundary") {
                `when`("the boundary block publishes the event") {
                    then("the dispatcher runs it only after the block has returned") {
                        val event = createOpenViewEvent()
                        ViewOpenDeferral.afterBoundary {
                            eventPublisher.publishEvent(event)
                            verify(exactly = 0) { messageDispatcher.dispatchImmediate(event = event.payload) }
                        }
                        verify(exactly = 1) { messageDispatcher.dispatchImmediate(event = event.payload) }
                    }
                }
            }

            given("a decline modal that failed to open after the interaction transaction ended") {
                `when`("its failure event is published outside any transaction") {
                    then("the fallback notice still reaches the outbox in its own transaction") {
                        val basicInfo = createCommandBasicInfo()
                        eventPublisher.publishEvent(
                            DeclineModalOpenFailedEvent(
                                meetingIdempotencyKey = UUID.randomUUID(),
                                participantUserId = basicInfo.publisherId,
                                apiAppId = basicInfo.appId,
                                channel = basicInfo.channel,
                                idempotencyKey = basicInfo.idempotencyKey,
                                reason = "expired_trigger_id",
                            ),
                        )

                        val idempotencyKey = basicInfo.idempotencyKey.toString()
                        val rows = outboxRepository.findAll().filter { it.idempotencyKey == idempotencyKey }
                        rows shouldHaveSize 1
                        outboxRepository.deleteAll(rows)
                    }
                }
            }

            given("AI turns requested from mention transactions") {
                `when`("one transaction rolls back and the next one commits") {
                    then("only the committed request reaches the agent, on the bounded agent-turn executor") {
                        val prompts = ConcurrentLinkedQueue<String>()
                        val threadNames = ConcurrentLinkedQueue<String>()
                        every { agentGateway.converse(request = any()) } answers {
                            threadNames += Thread.currentThread().name
                            prompts += firstArg<AgentTurnRequest>().prompt
                            AgentTurnResult.Failed(code = "smoke", message = "smoke")
                        }

                        runCatching {
                            transactionTemplate.executeWithoutResult {
                                eventPublisher.publishEvent(createAgentConverseRequestEvent(prompt = "rolled back"))
                                error("mention handling failed")
                            }
                        }
                        transactionTemplate.executeWithoutResult {
                            eventPublisher.publishEvent(createAgentConverseRequestEvent(prompt = "committed"))
                        }

                        eventually(5.seconds) { prompts.toList() shouldContain "committed" }
                        prompts.toList() shouldBe listOf("committed")
                        threadNames.toList().single() shouldStartWith "agent-turn-"
                    }
                }
            }

            given("an outbound message published inside a transaction") {
                `when`("the transaction commits") {
                    then("the BEFORE_COMMIT listener writes its outbox row in that transaction") {
                        val basicInfo = createCommandBasicInfo()
                        val event =
                            OutboundMessageEnqueued(
                                idempotencyKey = basicInfo.idempotencyKey,
                                payload =
                                    OutboundMessageEnqueuedPayload(
                                        message =
                                            OutboundMessage.ChannelMessage(
                                                target = ConversationTarget(id = basicInfo.channel),
                                                content = MessageContent.Text(headline = null, markdown = "smoke"),
                                            ),
                                        basicInfo = basicInfo,
                                    ),
                            )

                        transactionTemplate.executeWithoutResult { eventPublisher.publishEvent(event) }

                        val idempotencyKey = basicInfo.idempotencyKey.toString()
                        val rows = outboxRepository.findAll().filter { it.idempotencyKey == idempotencyKey }
                        rows shouldHaveSize 1
                        outboxRepository.deleteAll(rows)
                    }
                }
            }
        })
