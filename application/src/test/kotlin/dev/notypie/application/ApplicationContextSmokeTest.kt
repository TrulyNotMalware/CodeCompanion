package dev.notypie.application

import com.ninjasquad.springmockk.MockkBean
import com.zaxxer.hikari.HikariDataSource
import dev.notypie.application.service.agent.AgentConverseService
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.aop.support.AopUtils
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
import java.time.Clock
import java.time.ZoneId

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:application-smoke;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.threads.virtual.enabled=true",
        "spring.task.scheduling.pool.size=4",
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

                    then("AgentConverseService is proxied so @Async takes effect") {
                        AopUtils.isAopProxy(context.getBean(AgentConverseService::class.java)) shouldBe true
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
