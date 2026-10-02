package dev.notypie.impl.command

import com.slack.api.Slack
import com.slack.api.util.http.SlackHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.createActionEventPayloadContents
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.command.event.failOutput
import dev.notypie.impl.retry.RetryService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

        fun testSlack(
            methodsEndpointUrlPrefix: String = "http://127.0.0.1:$port/api/",
            callTimeout: Duration = SLACK_CALL_TIMEOUT,
        ): Slack =
            slackClient(
                config =
                    slackConfig(callTimeout = callTimeout).apply {
                        this.methodsEndpointUrlPrefix = methodsEndpointUrlPrefix
                        isPrettyResponseLoggingEnabled = false
                    },
            )

        fun toLoopback(client: OkHttpClient): OkHttpClient =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val rewritten =
                        original.url
                            .newBuilder()
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(port)
                            .build()
                    chain.proceed(original.newBuilder().url(rewritten).build())
                }.build()

        val slack = testSlack()
        val loopbackClient = toLoopback(client = responseUrlClient(slack = slack))
        val sleeps = mutableListOf<Duration>()
        val unknownOutcomes = ConcurrentLinkedDeque<String>()

        fun dispatcher(sleeper: (Duration) -> Unit = { sleeps.add(it) }) =
            ApplicationMessageDispatcher(
                botToken = "xoxb-test",
                applicationEventPublisher = mockk(relaxed = true),
                retryService = RetryService(),
                onOutcomeUnknown = { unknownOutcomes.add(it) },
                slack = slack,
                okHttpClient = loopbackClient,
                sleeper = sleeper,
            )

        val defaultDispatcher = dispatcher()

        fun reset() {
            responses.clear()
            sleeps.clear()
            unknownOutcomes.clear()
            calls.set(0)
        }

        fun channelMessage() =
            createPostEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                body = mapOf("text" to "hi"),
            )

        fun ephemeralMessage() =
            createPostEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                body = mapOf("text" to "hi", "user" to "U1"),
                messageType = MessageType.EPHEMERAL_MESSAGE,
            )

        fun messageUpdate() =
            createPostEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                body = mapOf("text" to "hi", "ts" to "1700000000.000100"),
                messageType = MessageType.UPDATE_MESSAGE,
            )

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
            val values = listOf("99999999999999999", "999999999999999999999999")
            values.forEach { value ->
                responses.add(status(code = 429, body = RATE_LIMITED_JSON, headers = arrayOf("Retry-After" to value)))
            }

            `when`("a channel message is dispatched for each value") {
                val outputs = values.map { defaultDispatcher.dispatch(event = channelMessage()) }

                then("the wait is capped at the outbox bound when it is parsed") {
                    outputs.map { it.retryAfter() } shouldBe listOf(MAX_RETRY_AFTER, MAX_RETRY_AFTER)
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
                    calls.get() shouldBe 1
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

        given("chat.postMessage answers HTTP 500, which Slack may answer after posting") {
            reset()
            repeat(3) { responses.add(status(code = 500, body = "boom")) }

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is not resent and ends as outcome unknown") {
                    output.isTransientExhausted() shouldBe false
                    output.errorReason shouldBe "$OUTCOME_UNKNOWN_REASON: http_500"
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.postMessage answers 200 with a body the SDK cannot parse") {
            listOf("not JSON" to "<html>upstream error</html>", "empty" to "").forEach { (kind, body) ->
                `when`("a channel message is dispatched and the body is $kind") {
                    reset()
                    repeat(3) { responses.add(status(code = 200, body = body)) }
                    val output = defaultDispatcher.dispatch(event = channelMessage())

                    then("Slack has answered, so it is not resent and ends as outcome unknown") {
                        output.isTransientExhausted() shouldBe false
                        output.errorReason shouldStartWith OUTCOME_UNKNOWN_REASON
                        calls.get() shouldBe 1
                    }
                }
            }
        }

        given("chat.update keeps answering 200 with a body the SDK cannot parse") {
            listOf("not JSON" to "<html>upstream error</html>", "empty" to "").forEach { (kind, body) ->
                `when`("a message update is dispatched and the body is $kind") {
                    reset()
                    repeat(3) { responses.add(status(code = 200, body = body)) }
                    val output = defaultDispatcher.dispatch(event = messageUpdate())

                    then("the idempotent update is retried and ends as a transient outcome") {
                        output.isTransientExhausted() shouldBe true
                        calls.get() shouldBe 3
                    }
                }
            }
        }

        given("chat.postMessage answers ok=false without an error field") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false}"""))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is a plain rejection, not an unreadable body or an outcome unknown") {
                    output.ok shouldBe false
                    output.errorReason shouldBe UNSPECIFIED_ERROR_REASON
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.update keeps answering HTTP 500") {
            reset()
            repeat(3) { responses.add(status(code = 500, body = "boom")) }

            `when`("a message update is dispatched") {
                val output = defaultDispatcher.dispatch(event = messageUpdate())

                then("the idempotent update is retried and ends as a transient outcome the outbox retries later") {
                    output.isTransientExhausted() shouldBe true
                    output.isRateLimited() shouldBe false
                    calls.get() shouldBe 3
                }
            }
        }

        given("chat.postMessage answers 200 ok=false internal_error, which may have partly succeeded") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))

            `when`("a channel message is dispatched") {
                val output = defaultDispatcher.dispatch(event = channelMessage())

                then("it is not resent and ends as outcome unknown") {
                    output.errorReason shouldBe "$OUTCOME_UNKNOWN_REASON: internal_error"
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.update answers 200 ok=false internal_error once and then succeeds") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))
            responses.add(jsonOk())

            `when`("a message update is dispatched") {
                val output = defaultDispatcher.dispatch(event = messageUpdate())

                then("the idempotent update is retried") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
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

        fun impatientDispatcher(
            methodsEndpointUrlPrefix: String = "http://127.0.0.1:$port/api/",
            slack: Slack =
                testSlack(methodsEndpointUrlPrefix = methodsEndpointUrlPrefix, callTimeout = Duration.ofMillis(200L)),
        ) = ApplicationMessageDispatcher(
            botToken = "xoxb-test",
            applicationEventPublisher = mockk(relaxed = true),
            retryService = RetryService(),
            onOutcomeUnknown = { unknownOutcomes.add(it) },
            slack = slack,
            okHttpClient = toLoopback(client = responseUrlClient(slack = slack)),
        )

        fun stallAfterRequest(release: CountDownLatch): (HttpExchange) -> Unit =
            { exchange -> release.await(5L, TimeUnit.SECONDS).also { exchange.close() } }

        given("chat.postMessage stalls longer than the call timeout after the request was sent") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add { exchange -> release.await(5L, TimeUnit.SECONDS).also { exchange.close() } } }

            `when`("a channel message is dispatched") {
                val output = impatientDispatcher().dispatch(event = channelMessage())
                release.countDown()

                then("it is not resent, because Slack may already have posted it") {
                    output.ok shouldBe false
                    output.isTransientExhausted() shouldBe false
                    output.errorReason shouldStartWith OUTCOME_UNKNOWN_REASON
                    calls.get() shouldBe 1
                }
            }
        }

        given("chat.postEphemeral stalls longer than the call timeout after the request was sent") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add(stallAfterRequest(release = release)) }

            `when`("an ephemeral message is dispatched") {
                val output = impatientDispatcher().dispatch(event = ephemeralMessage())
                release.countDown()

                then("it is not resent, because Slack may already have shown it") {
                    output.errorReason shouldStartWith OUTCOME_UNKNOWN_REASON
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url stalls longer than the call timeout after the request was sent") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add(stallAfterRequest(release = release)) }

            `when`("an action response is dispatched") {
                val output = impatientDispatcher().dispatch(event = actionResponse())
                release.countDown()

                then("it is not resent, because Slack may already have applied it") {
                    output.errorReason shouldStartWith OUTCOME_UNKNOWN_REASON
                    calls.get() shouldBe 1
                }
            }
        }

        given("DNS answers only after the call timeout, and then fails") {
            reset()
            val config =
                slackConfig(callTimeout = Duration.ofMillis(200L)).apply {
                    methodsEndpointUrlPrefix = "http://slack.invalid/api/"
                    isPrettyResponseLoggingEnabled = false
                }
            val slowDns =
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        TimeUnit.MILLISECONDS.sleep(400L)
                        throw UnknownHostException(hostname)
                    }
                }
            val slowDnsSlack =
                Slack.getInstance(
                    config,
                    SlackHttpClient(slackOkHttpClient(config = config).newBuilder().dns(slowDns).build()),
                )

            `when`("a channel message is dispatched") {
                val output = impatientDispatcher(slack = slowDnsSlack).dispatch(event = channelMessage())

                then("the timeout-wrapped failure is known to precede the request, so it is retried as never sent") {
                    output.isTransientExhausted() shouldBe true
                }
            }
        }

        given("a server that reads the request and then drops the connection without answering") {
            reset()
            val accepted = AtomicInteger(0)
            val dropper = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            val dropperThread =
                Thread {
                    while (!dropper.isClosed) {
                        runCatching {
                            dropper.accept().use { socket ->
                                accepted.incrementAndGet()
                                socket.getInputStream().read(ByteArray(8 * 1024))
                            }
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            `when`("a channel message is dispatched") {
                val output =
                    impatientDispatcher(methodsEndpointUrlPrefix = "http://127.0.0.1:${dropper.localPort}/api/")
                        .dispatch(event = channelMessage())
                dropper.close()
                dropperThread.join(1_000L)

                then("neither OkHttp nor the dispatcher sends it again, and the outcome is unknown") {
                    output.errorReason shouldStartWith OUTCOME_UNKNOWN_REASON
                    accepted.get() shouldBe 1
                }
            }
        }

        fun resettingDispatcher(errorCode: ErrorCode): ApplicationMessageDispatcher {
            val config =
                slackConfig().apply {
                    methodsEndpointUrlPrefix = "http://127.0.0.1:$port/api/"
                    isPrettyResponseLoggingEnabled = false
                }
            val resetOnce = AtomicBoolean(true)
            val resettingClient =
                slackOkHttpClient(config = config)
                    .newBuilder()
                    .addNetworkInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        if (resetOnce.getAndSet(false)) {
                            response.close()
                            throw StreamResetException(errorCode = errorCode)
                        }
                        response
                    }.build()
            return ApplicationMessageDispatcher(
                botToken = "xoxb-test",
                applicationEventPublisher = mockk(relaxed = true),
                retryService = RetryService(),
                onOutcomeUnknown = { unknownOutcomes.add(it) },
                slack = Slack.getInstance(config, SlackHttpClient(resettingClient)),
                okHttpClient = loopbackClient,
            )
        }

        given("an HTTP/2 server resets the stream after the request headers were written") {
            `when`("the reset is REFUSED_STREAM") {
                reset()
                val output =
                    resettingDispatcher(errorCode = ErrorCode.REFUSED_STREAM).dispatch(event = channelMessage())

                then("it is retried as never processed and delivered") {
                    output.ok shouldBe true
                    calls.get() shouldBe 2
                }
            }

            `when`("the reset is any other error code") {
                reset()
                val output = resettingDispatcher(errorCode = ErrorCode.CANCEL).dispatch(event = channelMessage())

                then("it is not resent, because Slack may already have posted it") {
                    output.errorReason shouldBe "$OUTCOME_UNKNOWN_REASON: StreamResetException"
                    calls.get() shouldBe 1
                }
            }
        }

        given("dispatches that end with an unknown outcome") {
            `when`("chat.postMessage answers HTTP 500") {
                reset()
                responses.add(status(code = 500, body = "boom"))
                defaultDispatcher.dispatch(event = channelMessage())

                then("it is reported once under chat.postMessage") {
                    unknownOutcomes.toList() shouldBe listOf("chat.postMessage")
                }
            }

            `when`("chat.postEphemeral stalls after the request was sent") {
                reset()
                val release = CountDownLatch(1)
                repeat(3) { responses.add(stallAfterRequest(release = release)) }
                impatientDispatcher().dispatch(event = ephemeralMessage())
                release.countDown()

                then("it is reported once under chat.postEphemeral") {
                    unknownOutcomes.toList() shouldBe listOf("chat.postEphemeral")
                }
            }

            `when`("a response_url answers internal_error") {
                reset()
                responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))
                defaultDispatcher.dispatch(event = actionResponse())

                then("it is reported once under response_url") {
                    unknownOutcomes.toList() shouldBe listOf("response_url")
                }
            }

            `when`("chat.postMessage is rejected for good or delivered") {
                reset()
                responses.add(status(code = 200, body = """{"ok":false,"error":"channel_not_found"}"""))
                defaultDispatcher.dispatch(event = channelMessage())
                defaultDispatcher.dispatch(event = channelMessage())

                then("nothing is reported") {
                    unknownOutcomes.toList() shouldBe emptyList()
                }
            }
        }

        given("chat.update stalls longer than the call timeout on every attempt") {
            reset()
            val release = CountDownLatch(1)
            repeat(3) { responses.add(stallAfterRequest(release = release)) }

            `when`("a message update is dispatched") {
                val output = impatientDispatcher().dispatch(event = messageUpdate())
                release.countDown()

                then("the idempotent update is retried and ends as a transient outcome") {
                    output.isTransientExhausted() shouldBe true
                    calls.get() shouldBe 3
                }
            }
        }

        given("Slack refuses the connection") {
            reset()

            `when`("a channel message is dispatched") {
                val output =
                    impatientDispatcher(methodsEndpointUrlPrefix = "http://127.0.0.1:1/api/").dispatch(
                        event = channelMessage(),
                    )

                then("the unsent request is retried and ends as a transient outcome") {
                    output.isTransientExhausted() shouldBe true
                }
            }
        }

        given("the production Slack client and response_url client") {
            val production = slackClient()
            val responseClient = responseUrlClient(slack = production)

            then("stats are off, every call is bounded, and redirects are never followed") {
                production.config.isStatsEnabled shouldBe false
                production.config.httpClientCallTimeoutMillis shouldBe SLACK_CALL_TIMEOUT.toMillis().toInt()
                responseClient.callTimeoutMillis shouldBe SLACK_CALL_TIMEOUT.toMillis().toInt()
                responseClient.followRedirects shouldBe false
                responseClient.followSslRedirects shouldBe false
            }

            then("neither client lets OkHttp re-send a written request on its own") {
                production.httpClient.okHttpClient.retryOnConnectionFailure shouldBe false
                responseClient.retryOnConnectionFailure shouldBe false
            }
        }

        given("an attempt whose request-header write began but did not finish") {
            val request = Request.Builder().url("http://127.0.0.1/").build()
            val call = OkHttpClient().newCall(request)

            fun startHeaderWrite() {
                RequestProgressListener.reset()
                RequestProgressListener.callStart(call = call)
                RequestProgressListener.requestHeadersStart(call = call)
            }

            then("it still counts as never written, as when HTTP/2 cannot open a stream on a shut-down connection") {
                startHeaderWrite()
                RequestProgressListener.requestNeverWritten() shouldBe true
            }

            then("it counts as written once the headers are written") {
                startHeaderWrite()
                RequestProgressListener.requestHeadersEnd(call = call, request = request)
                RequestProgressListener.requestNeverWritten() shouldBe false
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

                then("only the documented acknowledgements count as success, so it fails once for good") {
                    output.ok shouldBe false
                    output.errorReason shouldBe "unexpected_body: http_200: <html>maintenance</html>"
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

        given("a response_url that answers 200 with internal_error") {
            reset()
            responses.add(status(code = 200, body = """{"ok":false,"error":"internal_error"}"""))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it may have been applied, so it is not resent") {
                    output.errorReason shouldBe "$OUTCOME_UNKNOWN_REASON: internal_error"
                    calls.get() shouldBe 1
                }
            }
        }

        given("a response_url that answers HTTP 502") {
            reset()
            responses.add(status(code = 502, body = "bad gateway"))

            `when`("an action response is dispatched") {
                val output = defaultDispatcher.dispatch(event = actionResponse())

                then("it may have been applied, so it is not resent") {
                    output.errorReason shouldBe "$OUTCOME_UNKNOWN_REASON: http_502"
                    calls.get() shouldBe 1
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

        given("the access-blocked outcome the relay holds instead of failing") {
            then("only a failed output whose reason is exactly ACCESS_BLOCKED_REASON is access-blocked") {
                failOutput(event = channelMessage(), reason = ACCESS_BLOCKED_REASON).isAccessBlocked() shouldBe true
                failOutput(event = channelMessage(), reason = "channel_not_found").isAccessBlocked() shouldBe false
                failOutput(event = channelMessage(), reason = RATE_LIMITED_REASON).isAccessBlocked() shouldBe false
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
