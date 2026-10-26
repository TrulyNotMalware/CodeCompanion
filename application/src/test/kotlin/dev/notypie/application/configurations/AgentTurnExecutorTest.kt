package dev.notypie.application.configurations

import ch.qos.logback.classic.Level
import dev.notypie.application.service.agent.AgentTurn
import dev.notypie.logging.captureLogs
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AgentTurnExecutorTest :
    BehaviorSpec({
        given("the production agent-turn executor") {
            val executor =
                AgentConfiguration(appConfig = AppConfig())
                    .agentTurnExecutor(meterRegistry = SimpleMeterRegistry())
                    .apply { initialize() }

            `when`("a turn throws out of its worker thread") {
                val worker = AtomicReference<Thread>()
                val started = CountDownLatch(1)
                val logs =
                    captureLogs(loggerName = AgentConfiguration::class.java.packageName) {
                        executor.execute(
                            AgentTurn(
                                start = {
                                    worker.set(Thread.currentThread())
                                    started.countDown()
                                    throw StackOverflowError("deep")
                                },
                                onDiscard = {},
                            ),
                        )
                        started.await(5L, TimeUnit.SECONDS)
                        worker.get().join(5_000L)
                    }
                val workerHandler =
                    executor.threadPoolExecutor.threadFactory
                        .newThread {}
                        .uncaughtExceptionHandler
                executor.shutdown()

                then("one ERROR names the agent-turn- thread and the failure, and every worker carries the handler") {
                    val error = logs.eventsAt(level = Level.ERROR).single()
                    error.formattedMessage shouldStartWith
                        "Agent turn worker ${worker.get().name} ended on an uncaught java.lang.StackOverflowError"
                    worker.get().name shouldStartWith "agent-turn-"
                    error.throwableProxy.message shouldBe "deep"
                    workerHandler shouldBeSameInstanceAs AGENT_TURN_UNCAUGHT_LOGGER
                }
            }
        }
    })
