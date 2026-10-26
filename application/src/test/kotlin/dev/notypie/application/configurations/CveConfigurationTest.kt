package dev.notypie.application.configurations

import dev.notypie.application.service.cve.CveTopicBootstrap
import dev.notypie.application.service.cve.collector.CveCollector
import dev.notypie.application.service.cve.notification.CveNotificationDispatcher
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

class CveConfigurationTest :
    BehaviorSpec({
        val contextRunner =
            ApplicationContextRunner()
                .withUserConfiguration(AppConfigBinding::class.java, CveConfiguration::class.java)
                .withBean(Clock::class.java, { Clock.systemUTC() })
                .withBean(SimpleMeterRegistry::class.java)
                .withBean(PlatformTransactionManager::class.java, { mockk(relaxed = true) })
                .withBean(AgentGateway::class.java, { mockk(relaxed = true) })
                .withBean(CveTopicRepository::class.java, { mockk(relaxed = true) })
                .withBean(CveEventRepository::class.java, { mockk(relaxed = true) })
                .withBean(CveCollectLedgerRepository::class.java, { mockk(relaxed = true) })
                .withBean(CveDeliveryRepository::class.java, { mockk(relaxed = true) })
                .withBean(MessageOutboxRepository::class.java, { mockk(relaxed = true) })
                .withBean(OutboundMessagePort::class.java, { mockk(relaxed = true) })

        listOf("true", "TRUE", "yes", "on", "1").forEach { value ->
            given("slack.app.cve.enabled=$value") {
                then("the collector, dispatcher and bootstrap beans exist") {
                    contextRunner.withPropertyValues("slack.app.cve.enabled=$value").run { context ->
                        context.startupFailure shouldBe null
                        context.getBeansOfType(CveCollector::class.java).size shouldBe 1
                        context.getBeansOfType(CveNotificationDispatcher::class.java).size shouldBe 1
                        context.getBeansOfType(CveTopicBootstrap::class.java).size shouldBe 1
                    }
                }
            }
        }

        listOf("false", "no", "off", "0").forEach { value ->
            given("slack.app.cve.enabled=$value") {
                then("no CVE bean exists") {
                    contextRunner.withPropertyValues("slack.app.cve.enabled=$value").run { context ->
                        context.startupFailure shouldBe null
                        context.getBeansOfType(CveCollector::class.java).size shouldBe 0
                        context.getBeansOfType(CveNotificationDispatcher::class.java).size shouldBe 0
                        context.getBeansOfType(CveTopicBootstrap::class.java).size shouldBe 0
                    }
                }
            }
        }

        given("slack.app.cve.enabled is absent") {
            then("no CVE bean exists") {
                contextRunner.run { context ->
                    context.startupFailure shouldBe null
                    context.getBeansOfType(CveCollector::class.java).size shouldBe 0
                }
            }
        }
    })
