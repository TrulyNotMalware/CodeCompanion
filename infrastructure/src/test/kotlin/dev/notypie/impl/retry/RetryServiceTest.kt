package dev.notypie.impl.retry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.core.retry.RetryException
import java.io.IOException
import java.time.Duration
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
                then("without any recovery, it throws RetryException carrying the original exception as cause") {
                    val original = IllegalStateException("original")
                    val thrown =
                        shouldThrow<RetryException> {
                            retryService.execute(
                                action = { throw original },
                                initialDelay = 1L,
                            )
                        }
                    thrown.cause shouldBeSameInstanceAs original
                }
                then("a non-retryable exception is still wrapped with the original as cause") {
                    val original = IllegalStateException("not retryable")
                    var attempts = 0
                    val thrown =
                        shouldThrow<RetryException> {
                            retryService.execute(
                                action = {
                                    attempts++
                                    throw original
                                },
                                exceptions = listOf(IOException::class.java),
                            )
                        }
                    thrown.cause shouldBeSameInstanceAs original
                    attempts shouldBe 1
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

        given("the longest a run can take when every attempt fails") {
            then("it is every attempt at its timeout plus each backoff at its jitter maximum") {
                retryTimeBound(attemptTimeout = Duration.ofSeconds(6L)) shouldBe Duration.ofMillis(18_320L)
                retryTimeBound(attemptTimeout = Duration.ZERO, maxAttempts = 3L) shouldBe Duration.ofMillis(320L)
                retryTimeBound(attemptTimeout = Duration.ofSeconds(6L), maxAttempts = 1L) shouldBe
                    Duration.ofSeconds(6L)
            }
        }
    })
