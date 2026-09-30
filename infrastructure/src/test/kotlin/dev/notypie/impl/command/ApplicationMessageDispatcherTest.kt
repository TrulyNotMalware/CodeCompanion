package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.util.http.SlackHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.createActionEventPayloadContents
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.retry.RetryService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApplicationMessageDispatcherTest :
    BehaviorSpec({
        val responses = ConcurrentLinkedDeque<(HttpExchange) -> Unit>()
        val calls = AtomicInteger(0)
        val serverExecutor = Executors.newCachedThreadPool()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = serverExecutor
                createContext("/") { exchange ->
                    calls.incrementAndGet()
                    exchange.requestBody.readBytes()
                    (responses.pollFirst() ?: jsonOk())(exchange)
                }
                start()
            }
        afterSpec {
            server.stop(0)
            serverExecutor.shutdownNow()
        }
        val port = server.address.port
        val slackResponseUrl = "https://hooks.slack.com/actions/T0001/1234/token"

        fun fakeSlack(callTimeout: Duration = SLACK_CALL_TIMEOUT) =
            slackClient(callTimeout = callTimeout) {
                methodsEndpointUrlPrefix = "http://127.0.0.1:$port/api/"
                isPrettyResponseLoggingEnabled = false
            }

        val slack = fakeSlack()
        val closedPort = ServerSocket(0).use { it.localPort }
        val attempts = AtomicInteger(0)

        // Every attempt is counted; with refuseFirst the first one is sent to a closed port, a real connect failure.
        fun OkHttpClient.toLoopback(refuseFirst: Boolean = false): OkHttpClient =
            newBuilder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val target = if (refuseFirst && attempts.incrementAndGet() == 1) closedPort else port
                    val rewritten =
                        original.url
                            .newBuilder()
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(target)
                            .build()
                    chain.proceed(original.newBuilder().url(rewritten).build())
                }.build()

        val loopbackClient = responseUrlClient(slack = slack).toLoopback()
        val sleeps = mutableListOf<Duration>()

        fun dispatcher(
            sleeper: (Duration) -> Unit = { sleeps.add(it) },
            client: Slack = slack,
            responseClient: OkHttpClient = loopbackClient,
        ) = ApplicationMessageDispatcher(
            botToken = "xoxb-test",
            applicationEventPublisher = mockk(relaxed = true),
            retryService = RetryService(),
            slack = client,
            okHttpClient = responseClient,
            sleeper = sleeper,
        )

        val defaultDispatcher = dispatcher()

        fun reset() {
            responses.clear()
            sleeps.clear()
            calls.set(0)
            attempts.set(0)
        }

        fun stallAfterReadingRequest(release: CountDownLatch): (HttpExchange) -> Unit =
            { exchange -> release.await(5L, TimeUnit.SECONDS).also { exchange.close() } }

        fun channelMessage() =
            createPostEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                body = mapOf("text" to "hi"),
            )

        fun updateMessage() = channelMessage().copy(messageType = MessageType.UPDATE_MESSAGE)

        fun actionResponse(responseUrl: String = slackResponseUrl) =
            createActionEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                body = "{}",
                responseUrl = responseUrl,
            )

        given("chat.* answers HTTP 429 with a short Retry-After and then succeeds") {
            reset()
            responses.add(status(code = 429, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to "2")))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it waits Retry-After once inline and delivers the message") {
                    output.ok shouldBe true
                    sleeps shouldBe listOf(Duration.ofSeconds(2L))
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.* answers HTTP 429 with a Retry-After above the inline limit") {
            reset()
            responses.add(status(code = 429, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to "120")))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it neither sleeps nor calls again and hands the row and its Retry-After to the sweep") {
                    output.isRateLimited() shouldBe true
                    output.retryAfter() shouldBe Duration.ofSeconds(120L)
                    sleeps shouldBe emptyList()
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.* answers HTTP 429 without Retry-After") {
            reset()
            responses.add(status(code = 429, body = RATE_LIMITED_JSON))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("the unknown window is not waited out inline") {
                    output.isRateLimited() shouldBe true
                    sleeps shouldBe emptyList()
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.* answers HTTP 429 with an HTTP-date Retry-After far in the future") {
            reset()
            responses.add(
                status(
                    code = 429,
                    body = RATE_LIMITED_JSON,
                    headers = arrayOf("Retry-After" to "Wed, 21 Oct 2099 07:28:00 GMT"),
                ),
            )

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("the date is honoured instead of degrading to an immediate retry, capped at the outbox bound") {
                    output.isRateLimited() shouldBe true
                    output.retryAfter() shouldBe MAX_RETRY_AFTER
                    sleeps shouldBe emptyList()
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.* answers HTTP 429 with an absurd Retry-After in seconds") {
            reset()
            listOf("99999999999999999", "999999999999999999999999").forEach { value ->
                responses.add(status(code = 429, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to value)))
            }

            `when`("a channel message is dispatched for each value") {
                val outputs = List(2) { defaultDispatcher.dispatch(event = channelMessage()) }

                then("the wait is clamped when parsed, so the relay's LocalDateTime arithmetic cannot overflow") {
                    outputs.forEach { output ->
                        output.isRateLimited() shouldBe true
                        output.retryAfter() shouldBe MAX_RETRY_AFTER
                        shouldNotThrowAny { LocalDateTime.now().plus(output.retryAfter()) }
                    }
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.* keeps answering HTTP 429 with a short Retry-After") {
            reset()
            repeat(3) {
                responses.add(status(code = 429, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to "1")))
            }

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it gives up after one wait with a rate-limited output, not an exception") {
                    output.isRateLimited() shouldBe true
                    sleeps shouldBe listOf(Duration.ofSeconds(1L))
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.* answers 200 ok=false ratelimited") {
            reset()
            responses.add(status(code = 200, body = RATE_LIMITED_JSON))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it takes the rate-limit path, not the transient retry path") {
                    output.isRateLimited() shouldBe true
                    sleeps shouldBe emptyList()
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.* answers 200 ok=false ratelimited with a short Retry-After and then succeeds") {
            reset()
            responses.add(status(code = 200, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to "1")))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("the header is read from the parsed response and waited out once") {
                    output.ok shouldBe true
                    sleeps shouldBe listOf(Duration.ofSeconds(1L))
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.* answers 200 ok=false with a permanent error") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"channel_not_found"}"""))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it fails once without any retry") {
                    output.ok shouldBe false
                    output.errorReason shouldBe "channel_not_found"
                    output.isAccessBlocked() shouldBe false
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.* refuses the bot token or workspace as a whole") {
            reset()
            val blockedCodes = listOf("invalid_auth", "token_revoked", "missing_scope", "ekm_access_denied")
            blockedCodes.forEach { code ->
                responses.add(status(code = 200, body = """{"ok":false,"error":"$code"}"""))
            }
            val reported = mutableListOf<String>()
            val reporting =
                ApplicationMessageDispatcher(
                    botToken = "xoxb-test",
                    applicationEventPublisher = mockk(relaxed = true),
                    retryService = RetryService(),
                    slack = slack,
                    okHttpClient = loopbackClient,
                    onAccessBlocked = { reported.add(it) },
                )

            `when`("one channel message per error code is dispatched") {
                val outputs = blockedCodes.map { reporting.dispatch(event = channelMessage()) }

                then("each is access-blocked for the relay to hold, reported once, and not retried here") {
                    outputs.forEach { output ->
                        output.isAccessBlocked() shouldBe true
                        output.ok shouldBe false
                    }
                    reported shouldBe blockedCodes
                    calls.get() shouldBe blockedCodes.size
                }
            }
        }

        given("chat.* answers 200 ok=false fatal_error, which may have partially succeeded") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"fatal_error"}"""))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is not retried, so the message cannot be posted twice") {
                    output.ok shouldBe false
                    output.errorReason shouldBe "fatal_error"
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.* answers 200 ok=false with a transient error and then succeeds") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"service_unavailable"}"""))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("the transient error is retried through RetryService") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.postMessage answers 200 ok=false internal_error, which may have partly succeeded") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is not resent and ends as outcome_unknown") {
                    output.isOutcomeUnknown() shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.update answers 200 ok=false internal_error and then succeeds") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))
            responses.add(jsonOk())

            `when`("a message update is dispatched") {
                val output = defaultDispatcher.dispatch(event = updateMessage())

                then("an idempotent update is retried through RetryService") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.* answers HTTP 503 once and then succeeds") {
            reset()
            responses.add(status(code = 503, body = "unavailable"))
            responses.add(jsonOk())

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("RetryService retries the 5xx") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }
        }

        given("chat.* keeps answering HTTP 503") {
            reset()
            repeat(3) { responses.add(status(code = 503, body = "unavailable")) }

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("the exhausted quick retries become a transient outcome the outbox retries later") {
                    output.isTransientExhausted() shouldBe true
                    output.isRateLimited() shouldBe false
                    calls.get() shouldBe 3
                }
            }
        }

        given("chat.postMessage answers HTTP 500, which Slack may have answered after posting") {
            reset()
            responses.add(status(code = 500, body = "boom"))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is not resent and ends as outcome_unknown, which the outbox treats as terminal") {
                    output.isOutcomeUnknown() shouldBe true
                    output.isTransientExhausted() shouldBe false
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.update keeps answering HTTP 500") {
            reset()
            repeat(3) { responses.add(status(code = 500, body = "boom")) }

            `when`("a message update is dispatched") {
                val output = defaultDispatcher.dispatch(event = updateMessage())

                then("an idempotent update is still retried and ends as a transient outcome") {
                    output.isTransientExhausted() shouldBe true
                    calls.get() shouldBe 3
                }
            }
        }

        given("chat.* answers a 4xx with a non-JSON body") {
            reset()
            responses.add(status(code = 404, body = "<html>not found</html>"))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is a permanent failure after one call") {
                    output.ok shouldBe false
                    output.isTransientExhausted() shouldBe false
                    output.errorReason shouldBe "http_404: <html>not found</html>"
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.postMessage stalls past the call timeout after Slack has read the whole request") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add(stallAfterReadingRequest(release = release)) }
            val impatient = dispatcher(client = fakeSlack(callTimeout = Duration.ofMillis(200L)))

            `when`("a channel message is dispatched") {
                val output = impatient.dispatch(event = channelMessage())
                release.countDown()

                then("the call is cut once and not resent, because Slack may already have posted it") {
                    output.isOutcomeUnknown() shouldBe true
                    output.isTransientExhausted() shouldBe false
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.update stalls past the call timeout on every attempt") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add(stallAfterReadingRequest(release = release)) }
            val impatient = dispatcher(client = fakeSlack(callTimeout = Duration.ofMillis(200L)))

            `when`("a message update is dispatched") {
                val output = impatient.dispatch(event = updateMessage())
                release.countDown()

                then("an idempotent update is retried at each timeout and ends as a transient outcome") {
                    output.isTransientExhausted() shouldBe true
                    calls.get() shouldBe 3
                }
            }
        }

        given("chat.postMessage cannot connect on the first attempt") {
            reset()
            responses.add(jsonOk())
            val refusingSlack =
                Slack.getInstance(
                    slack.config,
                    SlackHttpClient(slack.httpClient.okHttpClient.toLoopback(refuseFirst = true)),
                )

            `when`("a channel message is dispatched") {
                val output = dispatcher(client = refusingSlack).dispatch(event = channelMessage())

                then("the request never reached Slack, so it is retried and delivered once") {
                    output.ok shouldBe true
                    attempts.get() shouldBe 2
                    calls.get() shouldBe 1
                }
            }
        }

        given("the production Slack client and response_url client") {
            val production = slackClient()
            val sdkClient = production.httpClient.okHttpClient
            val responseClient = responseUrlClient(slack = production)

            then("stats are off, every call is bounded and tracked, and redirects are never followed") {
                production.config.isStatsEnabled shouldBe false
                production.config.httpClientCallTimeoutMillis shouldBe SLACK_CALL_TIMEOUT.toMillis().toInt()
                sdkClient.callTimeoutMillis shouldBe SLACK_CALL_TIMEOUT.toMillis().toInt()
                sdkClient.followRedirects shouldBe false
                sdkClient.eventListenerFactory shouldBe RequestSendTracker
                responseClient.callTimeoutMillis shouldBe SLACK_CALL_TIMEOUT.toMillis().toInt()
                responseClient.followRedirects shouldBe false
                responseClient.followSslRedirects shouldBe false
                responseClient.eventListenerFactory shouldBe RequestSendTracker
            }
        }

        given("the thread is interrupted while waiting out a short Retry-After") {
            reset()
            responses.add(status(code = 429, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to "1")))
            val interruptible = dispatcher(sleeper = { throw InterruptedException("shutdown") })

            `when`("a channel message is dispatched") {
                val output = interruptible.dispatch(event = channelMessage())
                val interrupted = Thread.interrupted()

                then("the row stays rate-limited and the interrupt flag is restored") {
                    output.isRateLimited() shouldBe true
                    interrupted shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers HTTP 429") {
            reset()
            responses.add(status(code = 429, body = "rate limited", headers = arrayOf("Retry-After" to "60")))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it is rate-limited like chat.*") {
                    output.isRateLimited() shouldBe true
                    sleeps shouldBe emptyList()
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers 200 with a ratelimited JSON body") {
            reset()
            responses.add(status(code = 200, body = RATE_LIMITED_JSON))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("the body is classified as rate-limited") {
                    output.isRateLimited() shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers 200 with an ok=false body") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"expired_url"}"""))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it fails with Slack's error code as the reason") {
                    output.ok shouldBe false
                    output.errorReason shouldBe "expired_url"
                }
            }
        }

        given("a response_url that answers 200 with plain text ok") {
            reset()
            responses.add(status(code = 200, body = "ok"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it succeeds") {
                    output.ok shouldBe true
                }
            }
        }

        given("a response_url that answers 200 with a body that is neither ok nor Slack JSON") {
            reset()
            responses.add(status(code = 200, body = "<html>maintenance</html>"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("only the documented acknowledgements count as success, so it is a permanent failure") {
                    output.ok shouldBe false
                    output.isOutcomeUnknown() shouldBe false
                    output.errorReason shouldStartWith "unexpected_body: http_200: <html>maintenance"
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers 200 with a JSON ok body") {
            reset()
            responses.add(status(code = 200, body = """{"ok":true}"""))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it succeeds") {
                    output.ok shouldBe true
                }
            }
        }

        given("a response_url that answers HTTP 503 once and then ok") {
            reset()
            responses.add(status(code = 503, body = "unavailable"))
            responses.add(status(code = 200, body = "ok"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("the 5xx is retried") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }
        }

        given("a response_url that answers HTTP 500") {
            reset()
            responses.add(status(code = 500, body = "boom"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it is not resent and ends as outcome_unknown") {
                    output.isOutcomeUnknown() shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that stalls past the call timeout after reading the request") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add(stallAfterReadingRequest(release = release)) }
            val impatientSlack = fakeSlack(callTimeout = Duration.ofMillis(200L))
            val impatientClient = responseUrlClient(slack = impatientSlack).toLoopback()

            `when`("an action response is dispatched") {
                val output = dispatcher(responseClient = impatientClient).dispatch(event = actionResponse())
                release.countDown()

                then("it is cut once and not resent") {
                    output.isOutcomeUnknown() shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that cannot connect on the first attempt") {
            reset()
            responses.add(status(code = 200, body = "ok"))
            val refusingClient = responseUrlClient(slack = slack).toLoopback(refuseFirst = true)

            `when`("an action response is dispatched") {
                val output = dispatcher(responseClient = refusingClient).dispatch(event = actionResponse())

                then("the connect failure is retried and delivered once") {
                    output.ok shouldBe true
                    attempts.get() shouldBe 2
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers 200 with internal_error, which may have partly succeeded") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))
            responses.add(status(code = 200, body = "ok"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("the error code is classified like chat.postMessage: not resent, outcome_unknown") {
                    output.isOutcomeUnknown() shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers 200 with service_unavailable and then ok") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"service_unavailable"}"""))
            responses.add(status(code = 200, body = "ok"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("the error code is classified like chat.* and retried") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }
        }

        given("a response_url outside Slack's hooks hosts") {
            reset()

            `when`("action responses to a foreign host and to plain http are dispatched") {
                val foreign =
                    defaultDispatcher.dispatch(
                        event = actionResponse(responseUrl = "https://169.254.169.254/x"),
                    )
                val plainHttp =
                    defaultDispatcher.dispatch(event = actionResponse(responseUrl = "http://hooks.slack.com/actions/x"))
                val garbage = defaultDispatcher.dispatch(event = actionResponse(responseUrl = "not a url"))
                val userinfo =
                    defaultDispatcher.dispatch(
                        event = actionResponse(responseUrl = "https://hooks.slack.com@evil.example/actions/x"),
                    )
                val trailingDot =
                    defaultDispatcher.dispatch(
                        event = actionResponse(responseUrl = "https://hooks.slack.com./actions/x"),
                    )
                val explicitPort =
                    defaultDispatcher.dispatch(
                        event = actionResponse(responseUrl = "https://hooks.slack.com:8443/actions/x"),
                    )

                then("each is a permanent failure and no request is issued") {
                    listOf(foreign, plainHttp, garbage, userinfo, trailingDot, explicitPort).forEach { output ->
                        output.ok shouldBe false
                        output.isRateLimited() shouldBe false
                        output.errorReason shouldStartWith "response_url_rejected"
                    }
                    calls.get() shouldBe 0
                }
            }
        }

        given("a response_url written in upper case") {
            reset()
            responses.add(status(code = 200, body = "ok"))

            `when`("an action response is dispatched") {
                val output =
                    defaultDispatcher.dispatch(
                        event = actionResponse(responseUrl = "HTTPS://HOOKS.SLACK.COM/actions/T0001/1234/token"),
                    )

                then("the canonical host passes the allowlist and is delivered") {
                    output.ok shouldBe true
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that redirects to a foreign host") {
            reset()
            responses.add(
                status(code = 302, body = "", headers = arrayOf("Location" to "https://evil.example/collect")),
            )

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("the redirect is not followed and the 3xx is a permanent failure") {
                    output.ok shouldBe false
                    output.isRateLimited() shouldBe false
                    output.isTransientExhausted() shouldBe false
                    output.errorReason shouldStartWith "http_302"
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that streams an endless error body") {
            reset()
            responses.add(endlessBody(code = 400))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("only a bounded prefix is read into the failure reason") {
                    output.ok shouldBe false
                    output.errorReason shouldStartWith "http_400: xxxx"
                    output.errorReason.length shouldBeLessThan 300
                }
            }
        }
    })

private const val RATE_LIMITED_JSON = """{"ok":false,"error":"ratelimited"}"""

private fun jsonOk(): (HttpExchange) -> Unit = status(code = 200, body = """{"ok":true,"ts":"1700000000.000100"}""")

private fun status(
    code: Int,
    body: String,
    headers: Array<Pair<String, String>> = emptyArray(),
): (HttpExchange) -> Unit =
    { exchange ->
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

private fun endlessBody(code: Int): (HttpExchange) -> Unit =
    { exchange ->
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        exchange.sendResponseHeaders(code, 0L)
        try {
            exchange.responseBody.use { out -> repeat(16 * 1024) { out.write(chunk) } }
        } catch (_: IOException) {
            exchange.close()
        }
    }
