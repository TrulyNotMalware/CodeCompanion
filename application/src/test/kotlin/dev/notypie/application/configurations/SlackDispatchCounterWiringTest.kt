package dev.notypie.application.configurations

import dev.notypie.impl.command.ApplicationMessageDispatcher
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.retry.RetryService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.util.ReflectionTestUtils
import java.util.function.Supplier

class SlackDispatchCounterWiringTest :
    BehaviorSpec({
        given("the message dispatcher bean built by SlackRequestBuilderConfiguration in a Spring context") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val context =
                AnnotationConfigApplicationContext().apply {
                    addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor())
                    registerBean(AppConfig::class.java, Supplier { AppConfig() })
                    registerBean(RetryService::class.java, Supplier { RetryService() })
                    registerBean(MeterRegistry::class.java, Supplier<MeterRegistry> { registry })
                    register(SlackRequestBuilderConfiguration::class.java)
                    refresh()
                }
            afterSpec { context.close() }
            val dispatcher =
                context.getBean(MessageDispatcher::class.java).shouldBeInstanceOf<ApplicationMessageDispatcher>()

            `when`("the dispatcher reports an unknown outcome and a blocked token through its hooks") {
                @Suppress("UNCHECKED_CAST")
                val onOutcomeUnknown = ReflectionTestUtils.getField(dispatcher, "onOutcomeUnknown") as (String) -> Unit

                @Suppress("UNCHECKED_CAST")
                val onAccessBlocked = ReflectionTestUtils.getField(dispatcher, "onAccessBlocked") as (String) -> Unit
                onOutcomeUnknown("chat.postMessage")
                onAccessBlocked("invalid_auth")
                val scrape = registry.scrape()

                then("the context's registry exposes both counters under the names the runbook alerts on") {
                    scrape shouldContain
                        "codecompanion_slack_dispatch_outcome_unknown_total{method=\"chat.postMessage\"} 1.0"
                    scrape shouldContain "codecompanion_slack_dispatch_access_blocked_total{error=\"invalid_auth\"} 1.0"
                }
            }
        }
    })
