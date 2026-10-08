package dev.notypie.application.controllers

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

class GoogleOAuthCallbackControllerTest :
    BehaviorSpec({
        val callback = mockk<CalendarConnectionCallback>()
        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(GoogleOAuthCallbackController(calendarConnectionCallback = callback))
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
                    response.contentAsString shouldContain "/meetup calendar connect"
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
                    response.contentAsString shouldContain "/meetup calendar connect"
                }

                then("no request parameter is echoed") {
                    response.contentAsString shouldNotContain "c<b>"
                    response.contentAsString shouldNotContain "s<i>"
                }
            }
        }
    })
