package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.event.createActionEventPayloadContents
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.retry.RetryService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

class ApplicationMessageDispatcherTest :
    BehaviorSpec({
        val responses = ConcurrentLinkedDeque<(HttpExchange) -> Unit>()
        val calls = AtomicInteger(0)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    calls.incrementAndGet()
                    exchange.requestBody.readBytes()
                    (responses.pollFirst() ?: jsonOk())(exchange)
                }
                start()
            }
        afterSpec { server.stop(0) }
        val baseUrl = "http://127.0.0.1:${server.address.port}/api/"

        val slack =
            Slack.getInstance(
                SlackConfig().apply {
                    methodsEndpointUrlPrefix = baseUrl
                    isPrettyResponseLoggingEnabled = false
                    // Otherwise the SDK issues auth.test before every call to resolve the team for metrics.
                    isStatsEnabled = false
                },
            )
        val sleeps = mutableListOf<Duration>()
        val dispatcher =
            ApplicationMessageDispatcher(
                botToken = "xoxb-test",
                applicationEventPublisher = mockk(relaxed = true),
                retryService = RetryService(),
                slack = slack,
                sleeper = { sleeps.add(it) },
            )

        fun reset() {
            responses.clear()
            sleeps.clear()
            calls.set(0)
        }

        fun channelMessage() =
            createPostEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                body =
                    mapOf(
                        "text" to "hi",
                    ),
            )

        given("Slack answers HTTP 429 with Retry-After and then succeeds") {
            reset()
            responses.add(status(429, """{"ok":false,"error":"ratelimited"}""", "Retry-After" to "7"))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = dispatcher.dispatch(event = channelMessage())

                then("the dispatcher waits for exactly Retry-After and the message is delivered") {
                    output.ok shouldBe true
                    sleeps shouldBe listOf(Duration.ofSeconds(7L))
                    calls.get() shouldBe 2
                }
            }
        }

        given("Slack keeps answering HTTP 429") {
            reset()
            repeat(5) { responses.add(status(429, """{"ok":false,"error":"ratelimited"}""", "Retry-After" to "1")) }

            `when`("a channel message is dispatched") {
                val output = dispatcher.dispatch(event = channelMessage())

                then("it gives up after one bounded wait with a ratelimited failure, not an exception") {
                    output.isRateLimited() shouldBe true
                    sleeps.size shouldBe 1
                }
            }
        }

        given("Slack answers 200 ok=false with a permanent error") {
            reset()
            responses.add(status(200, """{"ok":false,"error":"channel_not_found"}"""))

            `when`("a channel message is dispatched") {
                val output = dispatcher.dispatch(event = channelMessage())

                then("it fails once without any retry") {
                    output.ok shouldBe false
                    output.errorReason shouldBe "channel_not_found"
                    calls.get() shouldBe 1
                }
            }
        }

        given("Slack answers 200 ok=false with a transient error and then succeeds") {
            reset()
            responses.add(status(200, """{"ok":false,"error":"internal_error"}"""))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = dispatcher.dispatch(event = channelMessage())

                then("the transient error is retried through RetryService") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }
        }

        given("a response_url that answers 200 with an ok=false body") {
            reset()
            responses.add(status(200, """{"ok":false,"error":"expired_url"}"""))

            `when`("an action response is dispatched") {
                val output =
                    dispatcher.dispatch(
                        event =
                            createActionEventPayloadContents(
                                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                                body = "{}",
                                responseUrl = "${baseUrl}response",
                            ),
                    )

                then("the body decides: it is a failure carrying Slack's reason") {
                    output.ok shouldBe false
                    output.errorReason shouldBe """{"ok":false,"error":"expired_url"}"""
                }
            }
        }

        given("a response_url that answers 200 ok") {
            reset()
            responses.add(status(200, "ok"))

            `when`("an action response is dispatched") {
                val output =
                    dispatcher.dispatch(
                        event =
                            createActionEventPayloadContents(
                                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                                body = "{}",
                                responseUrl = "${baseUrl}response",
                            ),
                    )

                then("it succeeds") {
                    output.ok shouldBe true
                }
            }
        }
    })

private fun jsonOk(): (HttpExchange) -> Unit = status(200, """{"ok":true,"ts":"1700000000.000100"}""")

private fun status(code: Int, body: String, vararg headers: Pair<String, String>): (HttpExchange) -> Unit =
    { exchange ->
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
