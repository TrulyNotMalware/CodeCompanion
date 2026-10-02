package dev.notypie.impl.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.common.jsonMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SidecarAgentClientTest :
    BehaviorSpec({
        lateinit var respond: (HttpExchange) -> Unit
        var capturedBody = ""
        var capturedAuthorization: String? = null
        var capturedUserId: String? = null
        var capturedTurnToken: String? = null

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/converse") { exchange ->
                    capturedBody = exchange.requestBody.readBytes().decodeToString()
                    capturedAuthorization = exchange.requestHeaders.getFirst("Authorization")
                    capturedUserId = exchange.requestHeaders.getFirst("X-User-Id")
                    capturedTurnToken = exchange.requestHeaders.getFirst("X-Turn-Token")
                    respond(exchange)
                }
                start()
            }
        afterSpec { server.stop(0) }

        val client =
            SidecarAgentClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                bearerSecret = "test-secret",
                requestTimeout = Duration.ofSeconds(5L),
            )

        fun sseResponse(body: String): (HttpExchange) -> Unit =
            { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }

        fun jsonResponse(status: Int, body: String): (HttpExchange) -> Unit =
            { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

        given("a turn requested on a thread that is being interrupted") {
            respond = sseResponse(body = "")

            `when`("converse runs") {
                Thread.currentThread().interrupt()
                val outcome =
                    runCatching {
                        client.converse(
                            request = AgentTurnRequest(sessionKey = "C1:interrupt", prompt = "hi"),
                        )
                    }
                val keptInterrupt = Thread.interrupted()

                then("the interrupt propagates with its flag instead of becoming a transport failure") {
                    outcome.exceptionOrNull().shouldBeInstanceOf<InterruptedException>()
                    keptInterrupt shouldBe true
                }
            }
        }

        given("a turn whose thread is interrupted while the stream is being read") {
            val streaming = CountDownLatch(1)
            val release = CountDownLatch(1)
            respond = { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("event: session\ndata: {\"sessionId\":\"sess-int\"}\n\n".toByteArray())
                exchange.responseBody.flush()
                streaming.countDown()
                release.await(10L, TimeUnit.SECONDS)
                exchange.close()
            }

            `when`("converse runs") {
                var outcome: Result<AgentTurnResult>? = null
                var keptInterrupt = false
                val turn =
                    Thread {
                        outcome =
                            runCatching {
                                client.converse(request = AgentTurnRequest(sessionKey = "C1:int", prompt = "hi"))
                            }
                        keptInterrupt = Thread.currentThread().isInterrupted
                    }.apply { start() }
                streaming.await(5L, TimeUnit.SECONDS)
                Thread.sleep(300L)
                turn.interrupt()
                turn.join(5_000L)
                release.countDown()

                then("the interrupt propagates with its flag instead of becoming a transport failure") {
                    outcome?.exceptionOrNull().shouldBeInstanceOf<InterruptedException>()
                    keptInterrupt shouldBe true
                }
            }
        }

        given("a turn that completes with done") {
            respond =
                sseResponse(
                    """
                    event: session
                    data: {"sessionId":"sess-1"}

                    : keep-alive

                    event: text
                    data: {"delta":"Hello "}

                    event: tool_use
                    data: {"name":"list_meetings","args":{},"toolUseId":"t1"}

                    event: tool_result
                    data: {"name":"list_meetings","ok":true,"toolUseId":"t1"}

                    event: text
                    data: {"delta":"there"}

                    event: done
                    data: {"finalText":"Hello there","usage":{"inputTokens":10,"outputTokens":5}}

                    """.trimIndent(),
                )

            `when`("converse") {
                val result =
                    client.converse(
                        request =
                            AgentTurnRequest(
                                sessionKey = "C1:1712345678.000100",
                                prompt = "hi",
                                sessionId = "sess-0",
                                userId = "U1",
                                appendSystemPrompt = "## Conversation context",
                            ),
                    )

                then("returns Completed with the done finalText, usage, and the new sessionId") {
                    val completed = result.shouldBeInstanceOf<AgentTurnResult.Completed>()
                    completed.finalText shouldBe "Hello there"
                    completed.sessionId shouldBe "sess-1"
                    completed.inputTokens shouldBe 10L
                    completed.outputTokens shouldBe 5L
                }

                then("sends the bearer secret and the caller identity header") {
                    capturedAuthorization shouldBe "Bearer test-secret"
                    capturedUserId shouldBe "U1"
                }

                then("sends no turn-token header when the request carries no token") {
                    capturedTurnToken shouldBe null
                }

                then("echoes sessionKey, prompt, resume sessionId, and the appended prompt in the body") {
                    @Suppress("UNCHECKED_CAST")
                    val body = jsonMapper.readValue(capturedBody, Map::class.java) as Map<String, Any?>
                    body["sessionKey"] shouldBe "C1:1712345678.000100"
                    body["prompt"] shouldBe "hi"
                    body["sessionId"] shouldBe "sess-0"
                    body["appendSystemPrompt"] shouldBe "## Conversation context"
                }
            }
        }

        given("a turn carrying an MCP scoped token") {
            respond =
                sseResponse(
                    """
                    event: session
                    data: {"sessionId":"sess-2"}

                    event: done
                    data: {"finalText":"ok","usage":{"inputTokens":1,"outputTokens":1}}

                    """.trimIndent(),
                )

            `when`("converse") {
                client.converse(
                    request =
                        AgentTurnRequest(
                            sessionKey = "C1:1712345678.000100",
                            prompt = "hi",
                            scopedToken = "v1.payload.signature",
                        ),
                )

                then("the token travels as the X-Turn-Token header, not in the body") {
                    capturedTurnToken shouldBe "v1.payload.signature"
                    capturedBody shouldNotContain "v1.payload.signature"
                }
            }
        }

        given("a first turn without a resume sessionId or appended prompt") {
            respond =
                sseResponse(
                    """
                    event: session
                    data: {"sessionId":"sess-new"}

                    event: done
                    data: {"finalText":"ok","usage":{"inputTokens":1,"outputTokens":1}}

                    """.trimIndent(),
                )

            `when`("converse") {
                client.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("the optional fields are omitted entirely (the sidecar forbids extra/unknown nulls)") {
                    capturedBody shouldContain "\"sessionKey\""
                    capturedBody shouldNotContain "sessionId"
                    capturedBody shouldNotContain "appendSystemPrompt"
                }
            }
        }

        given("a turn the sidecar rejects as busy before streaming (HTTP 429)") {
            respond = jsonResponse(status = 429, body = """{"code":"busy","message":"turn in flight"}""")

            `when`("converse") {
                val result = client.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("returns Busy") {
                    result shouldBe AgentTurnResult.Busy
                }
            }
        }

        given("a turn that ends with a terminal error frame") {
            respond =
                sseResponse(
                    """
                    event: session
                    data: {"sessionId":"sess-2"}

                    event: error
                    data: {"code":"timeout","message":"turn exceeded the ceiling"}

                    """.trimIndent(),
                )

            `when`("converse") {
                val result = client.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("returns Failed carrying the sidecar error code") {
                    val failed = result.shouldBeInstanceOf<AgentTurnResult.Failed>()
                    failed.code shouldBe "timeout"
                    failed.message shouldBe "turn exceeded the ceiling"
                }
            }
        }

        given("a turn whose error frame reports busy (post-stream race)") {
            respond =
                sseResponse(
                    """
                    event: error
                    data: {"code":"busy","message":"session busy"}

                    """.trimIndent(),
                )

            `when`("converse") {
                val result = client.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("returns Busy") {
                    result shouldBe AgentTurnResult.Busy
                }
            }
        }

        given("a stream that ends without any terminal event") {
            respond =
                sseResponse(
                    """
                    event: session
                    data: {"sessionId":"sess-3"}

                    """.trimIndent(),
                )

            `when`("converse") {
                val result = client.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("returns Failed(incomplete_stream)") {
                    result.shouldBeInstanceOf<AgentTurnResult.Failed>().code shouldBe
                        SidecarAgentClient.ERROR_CODE_INCOMPLETE_STREAM
                }
            }
        }

        given("a sidecar that sends a single data line longer than the frame bound") {
            respond = sseResponse(body = "event: text\ndata: {\"delta\":\"${"x".repeat(5_000)}\"}\n\n")
            val boundedClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerSecret = "test-secret",
                    requestTimeout = Duration.ofSeconds(5L),
                    maxFrameChars = 1_000,
                )

            `when`("converse") {
                val result = boundedClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("the stream is abandoned with Failed(stream_too_large)") {
                    result.shouldBeInstanceOf<AgentTurnResult.Failed>().code shouldBe
                        SidecarAgentClient.ERROR_CODE_STREAM_TOO_LARGE
                }
            }
        }

        given("a sidecar whose text deltas add up past the accumulated-text bound") {
            respond =
                sseResponse(
                    body =
                        (1..5).joinToString(separator = "") { "event: text\ndata: {\"delta\":\"0123456789\"}\n\n" } +
                            "event: done\ndata: {\"finalText\":\"\"}\n\n",
                )
            val boundedClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerSecret = "test-secret",
                    requestTimeout = Duration.ofSeconds(5L),
                    maxTextChars = 25,
                )

            `when`("converse") {
                val result = boundedClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("the stream is abandoned with Failed(stream_too_large) before the done frame") {
                    result.shouldBeInstanceOf<AgentTurnResult.Failed>().code shouldBe
                        SidecarAgentClient.ERROR_CODE_STREAM_TOO_LARGE
                }
            }
        }

        given("a stream with CRLF line endings whose text stays within the bounds") {
            respond =
                sseResponse(
                    body =
                        "event: text\r\ndata: {\"delta\":\"0123456789\"}\r\n\r\n" +
                            "event: done\r\ndata: {\"finalText\":\"\"}\r\n\r\n",
                )
            val boundedClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerSecret = "test-secret",
                    requestTimeout = Duration.ofSeconds(5L),
                    maxTextChars = 10,
                )

            `when`("converse") {
                val result = boundedClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("it completes with the accumulated text") {
                    result.shouldBeInstanceOf<AgentTurnResult.Completed>().finalText shouldBe "0123456789"
                }
            }
        }

        given("a sidecar that ends lines with a bare CR and keeps the connection open after done") {
            val release = CountDownLatch(1)
            respond = { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(
                    ("event: text\rdata: {\"delta\":\"hi\"}\r\r" + "event: done\rdata: {\"finalText\":\"\"}\r\r")
                        .toByteArray(),
                )
                exchange.responseBody.flush()
                release.await(10L, TimeUnit.SECONDS)
                exchange.close()
            }
            val impatientClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerSecret = "test-secret",
                    requestTimeout = Duration.ofSeconds(2L),
                )

            `when`("converse") {
                val result = impatientClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))
                release.countDown()

                then("the terminal frame completes the turn without waiting for a byte after the last CR") {
                    result.shouldBeInstanceOf<AgentTurnResult.Completed>().finalText shouldBe "hi"
                }
            }
        }

        given("a non-200 response with an oversized body") {
            respond = jsonResponse(status = 500, body = "y".repeat(100_000))

            `when`("converse") {
                val result = client.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("only a bounded prefix is kept in the failure") {
                    val failed = result.shouldBeInstanceOf<AgentTurnResult.Failed>()
                    failed.code shouldBe "http_500"
                    failed.message shouldBe "y".repeat(500)
                }
            }
        }

        given("a sidecar that sends the session event and then stalls") {
            val release = CountDownLatch(1)
            respond = { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("event: session\ndata: {\"sessionId\":\"sess-stall\"}\n\n".toByteArray())
                exchange.responseBody.flush()
                release.await(10L, TimeUnit.SECONDS)
                exchange.close()
            }
            val impatientClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerSecret = "test-secret",
                    requestTimeout = Duration.ofSeconds(1L),
                )

            `when`("converse") {
                val startedAt = System.nanoTime()
                val result = impatientClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
                release.countDown()

                then("the caller gets Failed(stream_timeout) within the budget instead of blocking forever") {
                    result.shouldBeInstanceOf<AgentTurnResult.Failed>().code shouldBe
                        SidecarAgentClient.ERROR_CODE_STREAM_TIMEOUT
                    (elapsed < Duration.ofSeconds(5L)) shouldBe true
                }
            }
        }

        given("a client configured without a bearer secret") {
            respond =
                sseResponse(
                    body =
                        "event: session\ndata: {\"sessionId\":\"sess-open\"}\n\n" +
                            "event: done\ndata: {\"finalText\":\"ok\",\"sessionId\":\"sess-open\"}\n\n",
                )
            val openClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerSecret = "",
                    requestTimeout = Duration.ofSeconds(5L),
                )

            `when`("converse") {
                openClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("it sends no Authorization header instead of an empty bearer token") {
                    capturedAuthorization shouldBe null
                }
            }
        }

        given("a sidecar that is not reachable") {
            val unreachableClient =
                SidecarAgentClient(
                    baseUrl = "http://127.0.0.1:1",
                    bearerSecret = "test-secret",
                    requestTimeout = Duration.ofSeconds(1L),
                )

            `when`("converse") {
                val result = unreachableClient.converse(request = AgentTurnRequest(sessionKey = "C1:x", prompt = "hi"))

                then("returns Failed(transport_error) instead of throwing") {
                    result.shouldBeInstanceOf<AgentTurnResult.Failed>().code shouldBe
                        SidecarAgentClient.ERROR_CODE_TRANSPORT
                }
            }
        }
    })
