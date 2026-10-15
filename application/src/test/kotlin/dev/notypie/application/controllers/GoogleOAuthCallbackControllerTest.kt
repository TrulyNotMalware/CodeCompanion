package dev.notypie.application.controllers

import dev.notypie.application.exception.ControllerAdvice
import dev.notypie.application.service.calendar.CalendarConnectionCallback
import dev.notypie.application.service.calendar.CalendarConnectionOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingServletRequestParameterException

class GoogleOAuthCallbackControllerTest :
    BehaviorSpec({
        val callback = mockk<CalendarConnectionCallback>()
        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(GoogleOAuthCallbackController(calendarConnectionCallback = callback))
                .setControllerAdvice(ControllerAdvice())
                .build()

        fun callback(vararg params: Pair<String, String>) =
            mockMvc
                .perform(
                    get(GoogleOAuthCallbackController.CALLBACK_PATH).apply {
                        params.forEach { (name, value) -> param(name, value) }
                    },
                ).andReturn()
                .response

        given("outcomes of the connection callback") {
            `when`("the connection completed") {
                every { callback.completeConnection(code = "c", state = "s", error = null) } returns
                    CalendarConnectionOutcome.CONNECTED
                val response = callback("code" to "c", "state" to "s")

                then("a 200 HTML page says so and is never cached") {
                    response.status shouldBe 200
                    response.contentType shouldStartWith "text/html"
                    response.getHeader("Cache-Control") shouldBe "no-store"
                    response.contentAsString shouldContain "Google Calendar connected"
                }
            }

            `when`("the user denied consent") {
                every { callback.completeConnection(code = null, state = "s", error = "access_denied") } returns
                    CalendarConnectionOutcome.DENIED
                val response = callback("state" to "s", "error" to "access_denied")

                then("a 200 page explains how to retry") {
                    response.status shouldBe 200
                    response.contentAsString shouldContain "Connection cancelled"
                    response.contentAsString shouldContain "/calendar connect"
                }
            }

            `when`("the user left the calendar scope unticked") {
                every { callback.completeConnection(code = "c", state = "s", error = null) } returns
                    CalendarConnectionOutcome.SCOPE_DENIED
                val response = callback("code" to "c", "state" to "s")

                then("a 200 page asks to allow calendar access and retry") {
                    response.status shouldBe 200
                    response.contentAsString shouldContain "Calendar access was not allowed"
                    response.contentAsString shouldContain "allow access to your calendar events"
                }
            }

            `when`("the state is invalid") {
                every { callback.completeConnection(code = "c", state = "<script>x</script>", error = null) } returns
                    CalendarConnectionOutcome.INVALID_STATE
                val response = callback("code" to "c", "state" to "<script>x</script>")

                then("a 400 page is returned without echoing any request parameter") {
                    response.status shouldBe 400
                    response.contentAsString shouldContain "no longer valid"
                    response.contentAsString shouldNotContain "<script>"
                }
            }

            `when`("Google rejected the exchange") {
                every { callback.completeConnection(code = "c", state = "s", error = null) } returns
                    CalendarConnectionOutcome.EXCHANGE_FAILED
                val response = callback("code" to "c", "state" to "s")

                then("a 502 page asks the user to retry") {
                    response.status shouldBe 502
                    response.contentAsString shouldContain "did not complete"
                }
            }

            `when`("the connection could not be stored after its retry") {
                every { callback.completeConnection(code = "c<b>", state = "s<i>", error = null) } returns
                    CalendarConnectionOutcome.STORE_FAILED
                val response = callback("code" to "c<b>", "state" to "s<i>")

                then("a 503 fixed-text page says the link is spent and points at connect, never cached") {
                    response.status shouldBe 503
                    response.contentType shouldStartWith "text/html"
                    response.getHeader("Cache-Control") shouldBe "no-store"
                    response.contentAsString shouldContain "The connection could not be saved"
                    response.contentAsString shouldContain "This link cannot be used again"
                    response.contentAsString shouldContain "/calendar connect"
                }

                then("no request parameter is echoed") {
                    response.contentAsString shouldNotContain "c<b>"
                    response.contentAsString shouldNotContain "s<i>"
                }
            }

            `when`("the Google account is not the requester's Slack account") {
                every { callback.completeConnection(code = "c<b>", state = "s<i>", error = null) } returns
                    CalendarConnectionOutcome.ACCOUNT_MISMATCH
                val response = callback("code" to "c<b>", "state" to "s<i>")

                then("a 403 fixed-text page asks for the Slack profile's e-mail and a new link, never cached") {
                    response.status shouldBe 403
                    response.contentType shouldStartWith "text/html"
                    response.getHeader("Cache-Control") shouldBe "no-store"
                    response.contentAsString shouldContain "This Google account does not match your Slack account"
                    response.contentAsString shouldContain "Sign in to Google with the e-mail address of your Slack"
                    response.contentAsString shouldContain "/calendar connect"
                    response.contentAsString shouldNotContain "@"
                }

                then("no request parameter is echoed") {
                    response.contentAsString shouldNotContain "c<b>"
                    response.contentAsString shouldNotContain "s<i>"
                }
            }

            `when`("the requester's Slack e-mail could not be read") {
                every { callback.completeConnection(code = "c", state = "s", error = null) } returns
                    CalendarConnectionOutcome.IDENTITY_UNAVAILABLE
                val response = callback("code" to "c", "state" to "s")

                then("a 503 fixed-text page says the link is spent and points at connect, never cached") {
                    response.status shouldBe 503
                    response.contentType shouldStartWith "text/html"
                    response.getHeader("Cache-Control") shouldBe "no-store"
                    response.contentAsString shouldContain "Your Slack e-mail could not be checked"
                    response.contentAsString shouldContain "This link cannot be used again"
                    response.contentAsString shouldContain "/calendar connect"
                    response.contentAsString shouldNotContain "@"
                }
            }
        }

        given("a callback that fails outside the classified outcomes") {
            `when`("the connection service throws an unexpected exception") {
                every { callback.completeConnection(code = "c<b>", state = "s<i>", error = null) } throws
                    RuntimeException("could not execute statement [update google_oauth_state set consumed_at=?]")
                val response = callback("code" to "c<b>", "state" to "s<i>")

                then("a 500 HTML page points at /calendar connect and is never cached, instead of the JSON error") {
                    response.status shouldBe 500
                    response.contentType shouldStartWith "text/html"
                    response.getHeader("Cache-Control") shouldBe "no-store"
                    response.contentAsString shouldContain "The connection could not be completed"
                    response.contentAsString shouldContain "Run /calendar connect in Slack for a new one."
                    response.contentAsString shouldNotContain "internal_error"
                }

                then("neither the exception text nor a request parameter is echoed") {
                    response.contentAsString shouldNotContain "google_oauth_state"
                    response.contentAsString shouldNotContain "c<b>"
                    response.contentAsString shouldNotContain "s<i>"
                }
            }

            `when`("a Spring MVC exception that carries its own 4xx status is raised") {
                every { callback.completeConnection(code = "c", state = "s", error = null) } throws
                    MissingServletRequestParameterException("code", "String")
                val response = callback("code" to "c", "state" to "s")

                then("its 400 stands instead of the 500 page") {
                    response.status shouldBe 400
                    response.contentAsString shouldNotContain "The connection could not be completed"
                }
            }

            `when`("the connection service is interrupted") {
                every { callback.completeConnection(code = "c", state = "s", error = null) } throws
                    InterruptedException()
                val response = callback("code" to "c", "state" to "s")
                val interrupted = Thread.interrupted()

                then("the same 500 page is served and the request thread's interrupt flag is restored") {
                    response.status shouldBe 500
                    response.contentAsString shouldContain "/calendar connect"
                    interrupted shouldBe true
                }
            }
        }
    })
