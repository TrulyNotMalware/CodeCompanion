package dev.notypie.application.security.mcp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class McpTurnTokenFilterTest :
    BehaviorSpec({
        val now = Instant.parse("2026-07-08T12:00:00Z")
        val codec =
            ScopedTurnTokenCodec(
                signingSecret = "test-signing-secret",
                tokenTtl = Duration.ofSeconds(300L),
                clockSkew = Duration.ofSeconds(30L),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        val token = codec.mint(userId = "U123", sessionKey = "C1:1751.0001", turnId = "turn-1")

        fun mcpRequest(remoteAddr: String, extraHeaders: Map<String, String> = emptyMap()) =
            MockHttpServletRequest("POST", "/mcp").apply {
                this.remoteAddr = remoteAddr
                addHeader("Authorization", "Bearer $token")
                extraHeaders.forEach { (name, value) -> addHeader(name, value) }
            }

        fun filterResult(filter: McpTurnTokenFilter, request: MockHttpServletRequest): Pair<Int, Boolean> {
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            return response.status to (chain.request != null)
        }

        given("the loopback-only MCP filter") {
            val filter = McpTurnTokenFilter(scopedTurnTokenCodec = codec, allowRemote = false)

            `when`("a loopback caller presents a valid turn token") {
                val (status, passed) = filterResult(filter = filter, request = mcpRequest(remoteAddr = "127.0.0.1"))

                then("the request reaches the MCP server") {
                    status shouldBe 200
                    passed shouldBe true
                }
            }

            listOf(
                "X-Forwarded-For" to "127.0.0.1",
                "Forwarded" to "for=127.0.0.1",
                "X-Real-IP" to "127.0.0.1",
            ).forEach { (header, value) ->
                `when`("the request carries $header, as a forged or proxied request would") {
                    val response = MockHttpServletResponse()
                    val chain = MockFilterChain()
                    filter.doFilter(
                        mcpRequest(remoteAddr = "127.0.0.1", extraHeaders = mapOf(header to value)),
                        response,
                        chain,
                    )

                    then("it is rejected even though the socket address is loopback") {
                        response.status shouldBe 401
                        response.contentAsString shouldContain "loopback-only"
                        (chain.request == null) shouldBe true
                    }
                }
            }

            `when`("a non-loopback caller presents a valid turn token") {
                val (status, passed) = filterResult(filter = filter, request = mcpRequest(remoteAddr = "10.0.0.5"))

                then("it is rejected") {
                    status shouldBe 401
                    passed shouldBe false
                }
            }
        }

        given("an MCP filter that allows remote callers") {
            val filter = McpTurnTokenFilter(scopedTurnTokenCodec = codec, allowRemote = true)

            `when`("a proxied request presents a valid turn token") {
                val (status, passed) =
                    filterResult(
                        filter = filter,
                        request =
                            mcpRequest(
                                remoteAddr = "10.0.0.5",
                                extraHeaders =
                                    mapOf(
                                        "X-Forwarded-For" to "10.1.1.1",
                                    ),
                            ),
                    )

                then("the token alone decides") {
                    status shouldBe 200
                    passed shouldBe true
                }
            }
        }
    })
