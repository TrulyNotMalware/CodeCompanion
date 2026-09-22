package dev.notypie.impl.retry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RetryServiceTest :
    BehaviorSpec({
        val retryService = RetryService()

        given("try retry") {
            val exceptionAction = { throw RuntimeException("Failure") }
            val successAction = { "Success" }
            val recoveryAction = { "Recovery" }
            `when`("run success action") {
                val result =
                    retryService.execute(
                        action = successAction,
                    )
                then("successfully works") {
                    result shouldBe "Success"
                }
            }

            `when`("run exception action") {
                then("without any recovery, should throw exception") {
                    shouldThrow<Exception> {
                        retryService.execute(
                            action = exceptionAction,
                        )
                    }
                }
                then("with recovery action, should return recovery response") {
                    val result =
                        retryService.execute(
                            action = exceptionAction,
                            recoveryCallBack = recoveryAction,
                        )
                    result shouldBe "Recovery"
                }
            }

            `when`("run exception action that fails N times and succeeds afterwards") {
                val maxFailures = 3L
                var failingCounter = 0
                val countExceptionAction = {
                    if (failingCounter < maxFailures) {
                        failingCounter++
                        throw RuntimeException("Failure $failingCounter")
                    } else {
                        "Success"
                    }
                }

                then("it should succeed after N failures") {
                    val result =
                        retryService.execute(
                            action = countExceptionAction,
                            recoveryCallBack = recoveryAction,
                            maxAttempts = maxFailures + 1,
                        )
                    result shouldBe "Success"
                }
            }

            `when`("an action never succeeds") {
                then("maxAttempts is the total number of executions, not the number of retries") {
                    var attempts = 0
                    retryService.execute(
                        action = {
                            attempts++
                            throw RuntimeException("always")
                        },
                        recoveryCallBack = recoveryAction,
                        maxAttempts = 2L,
                        initialDelay = 1L,
                    )
                    attempts shouldBe 2
                }
            }
        }

        given("two callers with different policies running at the same time") {
            `when`("one asks for 5 attempts and the other for 2") {
                val attemptsA = AtomicInteger(0)
                val attemptsB = AtomicInteger(0)
                val executor = Executors.newFixedThreadPool(2)

                fun run(counter: AtomicInteger, maxAttempts: Long) =
                    executor.submit(
                        Callable {
                            retryService.execute(
                                action = {
                                    counter.incrementAndGet()
                                    throw RuntimeException("always")
                                },
                                recoveryCallBack = { "recovered" },
                                maxAttempts = maxAttempts,
                                initialDelay = 20L,
                                jitter = 0L,
                            )
                        },
                    )
                val a = run(counter = attemptsA, maxAttempts = 5L)
                val b = run(counter = attemptsB, maxAttempts = 2L)
                a.get(10L, TimeUnit.SECONDS)
                b.get(10L, TimeUnit.SECONDS)
                executor.shutdownNow()

                then("each caller gets exactly its own policy") {
                    attemptsA.get() shouldBe 5
                    attemptsB.get() shouldBe 2
                }
            }
        }
    })
