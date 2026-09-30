package dev.notypie.application.security.mcp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.jetty.autoconfigure.JettyServerProperties
import org.springframework.boot.jetty.autoconfigure.JettyWebServerFactoryCustomizer
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
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

        fun mcpRequest(remoteAddr: String, forwardedFor: String? = null) =
            MockHttpServletRequest("POST", "/mcp").apply {
                this.remoteAddr = remoteAddr
                addHeader("Authorization", "Bearer $token")
                forwardedFor?.let { addHeader("X-Forwarded-For", it) }
            }

        given("McpTurnTokenFilter with allowRemote=false") {
            val filter = McpTurnTokenFilter(scopedTurnTokenCodec = codec, allowRemote = false)

            `when`("a pod-network client claims to be 127.0.0.1 through X-Forwarded-For") {
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(mcpRequest(remoteAddr = "10.244.1.7", forwardedFor = "127.0.0.1"), response, chain)

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

        // The filter trusts remoteAddr, so the container must never rewrite it from X-Forwarded-For. Boot does
        // exactly that on its own when it detects Kubernetes unless server.forward-headers-strategy says otherwise.
        given("Jetty forward-header handling when Boot detects Kubernetes") {
            fun useForwardHeaders(vararg yamlFiles: String): Boolean {
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
                return factory.isUseForwardHeaders
            }

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
