package dev.notypie.application.security.mcp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.jetty.autoconfigure.JettyServerProperties
import org.springframework.boot.jetty.autoconfigure.JettyWebServerFactoryCustomizer
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.web.servlet.ServletContextInitializer
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

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

        fun configuredJetty(vararg yamlFiles: String): JettyServletWebServerFactory {
            val environment = MockEnvironment().withProperty("spring.main.cloud-platform", "kubernetes")
            yamlFiles
                .flatMap { file -> YamlPropertySourceLoader().load(file, ClassPathResource(file)) }
                .forEach { source -> environment.propertySources.addLast(source) }
            val serverProperties =
                Binder
                    .get(environment)
                    .bind("server", ServerProperties::class.java)
                    .orElseGet { ServerProperties() }
            val factory = JettyServletWebServerFactory()
            JettyWebServerFactoryCustomizer(environment, serverProperties, JettyServerProperties()).customize(factory)
            return factory
        }

        fun throughJetty(factory: JettyServletWebServerFactory, forwardedFor: String): Pair<Int, String> {
            factory.port = 0
            factory.address = InetAddress.getLoopbackAddress()
            val seenRemoteAddr = AtomicReference("")
            val recorder =
                Filter { request, response, chain ->
                    seenRemoteAddr.set(request.remoteAddr)
                    chain.doFilter(request, response)
                }
            val filter = McpTurnTokenFilter(scopedTurnTokenCodec = codec, allowRemote = false)
            val server =
                factory.getWebServer(
                    ServletContextInitializer { context ->
                        context.addFilter("remoteAddrRecorder", recorder).addMappingForUrlPatterns(null, false, "/mcp")
                        context.addFilter("mcpTurnToken", filter).addMappingForUrlPatterns(null, true, "/mcp")
                        context
                            .addServlet(
                                "mcp",
                                object : HttpServlet() {
                                    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
                                        resp.status = HttpServletResponse.SC_OK
                                    }
                                },
                            ).addMapping("/mcp")
                    },
                )
            server.start()
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI.create("http://127.0.0.1:${server.port}/mcp"))
                        .header("Authorization", "Bearer $token")
                        .header("X-Forwarded-For", forwardedFor)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build()
                val status =
                    HttpClient
                        .newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode()
                return status to seenRemoteAddr.get()
            } finally {
                server.stop()
            }
        }

        given("the loopback-only MCP filter behind a real Jetty connector on Kubernetes") {
            `when`("the prod configuration serves a loopback peer whose X-Forwarded-For names a pod address") {
                val (status, remoteAddr) =
                    throughJetty(
                        factory = configuredJetty("application-prod.yaml", "application.yaml"),
                        forwardedFor = "10.244.1.7",
                    )

                then("the filter sees the socket peer, not the header, and still rejects the forwarded request") {
                    remoteAddr shouldBe "127.0.0.1"
                    status shouldBe 401
                }
            }

            `when`("forward headers are on, as Boot sets them on Kubernetes without a strategy") {
                val factory = configuredJetty()
                val forwardHeadersOn = factory.isUseForwardHeaders
                val (status, remoteAddr) = throughJetty(factory = factory, forwardedFor = "10.244.1.7")

                then("the header replaces remoteAddr, which is what the configured strategy prevents") {
                    forwardHeadersOn shouldBe true
                    remoteAddr shouldBe "10.244.1.7"
                    status shouldBe 401
                }
            }
        }

        given("Jetty forward-header handling when Boot detects Kubernetes") {
            listOf(
                listOf("application.yaml"),
                listOf("application-prod.yaml", "application.yaml"),
                listOf("application-dev.yaml", "application.yaml"),
            ).forEach { yamlFiles ->
                `when`("the configuration is ${yamlFiles.joinToString(separator = " over ")}") {
                    then("forward headers stay off, so the loopback check sees the socket peer") {
                        configuredJetty(*yamlFiles.toTypedArray()).isUseForwardHeaders shouldBe false
                    }
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
