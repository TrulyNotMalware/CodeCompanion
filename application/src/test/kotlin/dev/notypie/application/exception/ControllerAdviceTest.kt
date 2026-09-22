package dev.notypie.application.exception

import dev.notypie.domain.common.error.ExceptionArgument
import dev.notypie.exception.meeting.DatabaseException
import dev.notypie.exception.meeting.JpaErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.servlet.resource.NoResourceFoundException

class ControllerAdviceTest :
    BehaviorSpec({
        val advice = ControllerAdvice()

        given("a DatabaseException raised while handling a Slack request") {
            val exception =
                DatabaseException(
                    tableName = "meetings",
                    errorCode = JpaErrorCode.TABLE_NOT_FOUND,
                    details = listOf(ExceptionArgument(fieldName = "id", value = "42", reason = "not found")),
                )

            `when`("the advice handles it") {
                val response = advice.handleDatabaseException(e = exception)

                then("it responds 500 instead of an empty 200") {
                    response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                }

                then("the body carries a stable error marker") {
                    response.body shouldBe mapOf("error" to "internal_error")
                }
            }
        }

        given("an unexpected exception") {
            val exception = IllegalStateException("boom")

            `when`("the advice handles it") {
                val response = advice.handleUnexpected(e = exception)

                then("it responds 500 with the same error marker") {
                    response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                    response.body shouldBe mapOf("error" to "internal_error")
                }
            }
        }

        given("a Spring MVC exception that already carries a status") {
            val exception =
                NoResourceFoundException(HttpMethod.GET, "/does-not-exist", "does-not-exist")
            val request = ServletWebRequest(MockHttpServletRequest("GET", "/does-not-exist"))

            `when`("the inherited framework handler resolves it") {
                val response = advice.handleException(exception, request)

                then("the status stays 404 instead of collapsing into the 500 catch-all") {
                    response.shouldNotBeNull().statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        given("an UnsupportedSlackCommandTypeException") {
            val exception =
                UnsupportedSlackCommandTypeException(
                    rawCommandType = "bogus",
                    errorCode = PayloadParseErrorCode.UNSUPPORTED_SLACK_COMMAND_TYPE,
                    details = emptyList(),
                )

            `when`("the advice handles it") {
                val response = advice.handleUnsupportedSlackCommandType(e = exception)

                then("it responds 400 naming the command type") {
                    response.statusCode shouldBe HttpStatus.BAD_REQUEST
                    response.body shouldBe mapOf("error" to "Unsupported Slack command type: bogus")
                }
            }
        }
    })
