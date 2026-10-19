package dev.notypie.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class ExceptionBoundaryTest :
    BehaviorSpec({
        given("containFailure") {
            `when`("the block completes") {
                val failures = mutableListOf<Exception>()
                var ran = false
                containFailure(onFailure = { failures += it }) { ran = true }

                then("the block ran and nothing was reported") {
                    ran shouldBe true
                    failures.shouldBeEmpty()
                }
            }

            `when`("the block throws an Exception") {
                val failures = mutableListOf<Exception>()
                val thrown = IllegalStateException("row failed")
                containFailure(onFailure = { failures += it }) { throw thrown }

                then("the exception goes to onFailure and nothing escapes") {
                    failures shouldContainExactly listOf(thrown)
                }
            }

            `when`("the block throws an Error") {
                val failures = mutableListOf<Exception>()
                val thrown = StackOverflowError("deep")
                val escaped =
                    shouldThrow<StackOverflowError> {
                        containFailure(onFailure = { failures += it }) { throw thrown }
                    }

                then("the same Error escapes and onFailure never runs") {
                    escaped shouldBeSameInstanceAs thrown
                    failures.shouldBeEmpty()
                }
            }

            `when`("the block throws an InterruptedException") {
                val failures = mutableListOf<Exception>()
                val thrown = InterruptedException("stop")
                var keptInterrupt = false
                val escaped =
                    try {
                        shouldThrow<InterruptedException> {
                            containFailure(onFailure = { failures += it }) { throw thrown }
                        }
                    } finally {
                        keptInterrupt = Thread.interrupted()
                    }

                then("it is rethrown with the interrupt flag restored, and onFailure never runs") {
                    escaped shouldBeSameInstanceAs thrown
                    keptInterrupt shouldBe true
                    failures.shouldBeEmpty()
                }
            }
        }

        given("runCatchingExceptions") {
            `when`("the block returns") {
                val result = runCatchingExceptions { "value" }

                then("the value is a success") {
                    result.getOrNull() shouldBe "value"
                }
            }

            `when`("the block throws an Exception") {
                val thrown = IllegalArgumentException("bad input")
                val result = runCatchingExceptions<String> { throw thrown }

                then("the exception is the failure") {
                    result.exceptionOrNull() shouldBeSameInstanceAs thrown
                }
            }

            `when`("the block throws an Error") {
                val thrown = NotImplementedError("unfinished")
                val escaped = shouldThrow<NotImplementedError> { runCatchingExceptions<String> { throw thrown } }

                then("the same Error escapes instead of becoming a failure") {
                    escaped shouldBeSameInstanceAs thrown
                }
            }

            `when`("the block throws an InterruptedException") {
                val thrown = InterruptedException("stop")
                var keptInterrupt = false
                val escaped =
                    try {
                        shouldThrow<InterruptedException> { runCatchingExceptions<String> { throw thrown } }
                    } finally {
                        keptInterrupt = Thread.interrupted()
                    }

                then("it is rethrown with the interrupt flag restored") {
                    escaped shouldBeSameInstanceAs thrown
                    keptInterrupt shouldBe true
                }
            }
        }
    })
