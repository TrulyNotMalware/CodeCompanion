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

/**
 * Exercises the client against a real HTTP server (JDK built-in, no extra deps) speaking the
 * sidecar's SSE wire format — camelCase field names per `openapi.yaml`, `:keep-alive` comments,
 * and the session → text* → done|error event sequence.
 */
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

        given("a sidecar that is not reachable") {
            val unreachableClient =
                SidecarAgentClient(
                    // Reserved port on loopback: the connection is refused immediately.
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
