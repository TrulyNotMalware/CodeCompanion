package dev.notypie.application.security.mcp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.core.env.PropertySource
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

class McpTurnTokenFilterTest :
    BehaviorSpec({
        val codec =
            ScopedTurnTokenCodec(
                signingSecret = "test-signing-secret",
                tokenTtl = Duration.ofSeconds(300L),
                clockSkew = Duration.ofSeconds(30L),
                clock = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC),
            )
        val token = codec.mint(userId = "U123", sessionKey = "C1:1751.0001", turnId = "turn-1")

        fun mcpRequest(remoteAddr: String) =
            MockHttpServletRequest("POST", "/mcp").apply {
                this.remoteAddr = remoteAddr
                addHeader("Authorization", "Bearer $token")
            }

        given("McpTurnTokenFilter with allowRemote=false") {
            val filter = McpTurnTokenFilter(scopedTurnTokenCodec = codec, allowRemote = false)

            // The X-Forwarded-For spoof needs a real connector to mean anything (the filter never reads the header,
            // so a mock request proves nothing about it); it is covered through Jetty below (G11).
            `when`("a pod-network client presents a valid turn token") {
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(mcpRequest(remoteAddr = "10.244.1.7"), response, chain)

                then("the filter judges the connection's remoteAddr and rejects it") {
                    response.status shouldBe 401
                    response.contentAsString shouldContain "loopback-only"
                    chain.request shouldBe null
                }
            }

            `when`("a loopback client presents a valid turn token") {
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(mcpRequest(remoteAddr = "127.0.0.1"), response, chain)

                then("the request reaches the MCP endpoint") {
                    response.status shouldBe 200
                    (chain.request != null) shouldBe true
                }
            }
        }

        // Boot's Jetty factory as the application configures it on Kubernetes (cloud platform detected).
        fun configuredJetty(vararg yamlFiles: String): JettyServletWebServerFactory {
            val environment = MockEnvironment().withProperty("spring.main.cloud-platform", "kubernetes")
            yamlFiles
                .flatMap { file -> YamlPropertySourceLoader().load(file, ClassPathResource(file)) }
                .forEach { source: PropertySource<*> -> environment.propertySources.addLast(source) }
            val serverProperties =
                Binder
                    .get(environment)
                    .bind("server", ServerProperties::class.java)
                    .orElseGet { ServerProperties() }
            val factory = JettyServletWebServerFactory()
            JettyWebServerFactoryCustomizer(
                environment,
                serverProperties,
                JettyServerProperties(),
            ).customize(factory)
            return factory
        }

        fun useForwardHeaders(vararg yamlFiles: String): Boolean = configuredJetty(*yamlFiles).isUseForwardHeaders

        // Serves the filter on an ephemeral loopback port behind the factory's real connector (Jetty's
        // ForwardedRequestCustomizer when forward headers are on) and returns the status of one POST /mcp.
        fun statusThroughJetty(factory: JettyServletWebServerFactory, forwardedFor: String): Int {
            factory.port = 0
            factory.address = InetAddress.getLoopbackAddress()
            val filter = McpTurnTokenFilter(scopedTurnTokenCodec = codec, allowRemote = false)
            val server =
                factory.getWebServer(
                    ServletContextInitializer { context ->
                        context.addFilter("mcpTurnToken", filter).addMappingForUrlPatterns(null, false, "/mcp")
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
                return HttpClient
                    .newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode()
            } finally {
                server.stop()
            }
        }

        // G11: the filter trusts remoteAddr, so the spoof only matters if the container rewrites remoteAddr from
        // X-Forwarded-For. A test client is always a loopback peer, so the spoof is checked from the other side: a
        // pod address in the header must not reach the filter. The control run with forward headers on proves the
        // header does reach it when Jetty is told to honour it, so a passing case is not a vacuous one.
        given("the MCP filter behind a real Jetty connector") {
            `when`("the prod configuration serves a loopback peer whose X-Forwarded-For names a pod address") {
                val status =
                    statusThroughJetty(
                        factory = configuredJetty("application-prod.yaml", "application.yaml"),
                        forwardedFor = "10.244.1.7",
                    )

                then("the filter sees the socket peer, not the header, and lets the request through") {
                    status shouldBe 200
                }
            }

            `when`("forward headers are on, as Boot sets them on Kubernetes without a strategy") {
                val factory = configuredJetty()
                val forwardHeadersOn = factory.isUseForwardHeaders
                val status = statusThroughJetty(factory = factory, forwardedFor = "10.244.1.7")

                then("the header rewrites remoteAddr and the loopback check rejects it") {
                    forwardHeadersOn shouldBe true
                    status shouldBe 401
                }
            }
        }

        // The filter trusts remoteAddr, so the container must never rewrite it from X-Forwarded-For. Boot does
        // exactly that on its own when it detects Kubernetes unless server.forward-headers-strategy says otherwise.
        given("Jetty forward-header handling when Boot detects Kubernetes") {

            `when`("no forward-headers strategy is configured") {
                then("Boot enables forward headers, which lets X-Forwarded-For replace remoteAddr") {
                    useForwardHeaders() shouldBe true
                }
            }

            listOf(
                listOf("application.yaml"),
                listOf("application-prod.yaml", "application.yaml"),
                listOf("application-dev.yaml", "application.yaml"),
            ).forEach { yamlFiles ->
                `when`("the configuration is ${yamlFiles.joinToString(separator = " over ")}") {
                    then("forward headers stay off, so the loopback check sees the socket peer") {
                        useForwardHeaders(*yamlFiles.toTypedArray()) shouldBe false
                    }
                }
            }
        }
    })
