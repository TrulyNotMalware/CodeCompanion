package dev.notypie.impl.retry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.retry.support.RetryTemplate

class RetryServiceTest: BehaviorSpec({
    val retryTemplate = RetryTemplate()
    val retryService = RetryService(retryTemplate = retryTemplate)

    given("try retry"){
        val exceptionAction = { throw RuntimeException("Failure") }
        val successAction = { "Success" }
        val recoveryAction = { "Recovery" }
        `when`("run success action"){
            val result = retryService.execute(
                action = successAction
            )
            then("successfully works"){
                result shouldBe "Success"
            }
        }

        `when`("run exception action"){
            then("without any recovery, should throw exception"){
                shouldThrow<Exception> {
                    retryService.execute(
                        action = exceptionAction
                    )
                }
            }
            then("with recovery action, should return recovery response"){
                val result = retryService.execute(
                    action = exceptionAction,
                    recoveryCallBack = recoveryAction
                )
                result shouldBe "Recovery"
            }
        }

        `when`("run exception action that fails N times and succeeds afterwards") {
            val maxFailures = 3
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
                val result = retryService.execute(
                    action = countExceptionAction,
                    recoveryCallBack = recoveryAction, // Recovery if retry fails completely
                    maxAttempts = maxFailures+1
                )
                result shouldBe "Success"
            }
        }
    }

})
