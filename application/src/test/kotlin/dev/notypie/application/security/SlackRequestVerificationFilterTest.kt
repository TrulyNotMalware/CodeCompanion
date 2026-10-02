package dev.notypie.application.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.application.configurations.AppConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val SIGNING_SECRET = "secret"
private const val TIMESTAMP = "1714280000"
private const val EVENTS_PATH = "/api/slack/events"
private const val EVENT_BODY = """{"event_id":"Ev123","type":"event_callback"}"""
private const val WELL_FORMED_SIGNATURE = "v0=0000000000000000000000000000000000000000000000000000000000000000"

class SlackRequestVerificationFilterTest :
    BehaviorSpec({
        val clock = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP.toLong()), ZoneOffset.UTC)
        val signatureVerifier = SlackSignatureVerifier(clock = clock)

        fun appConfig(signingSecret: String = SIGNING_SECRET) =
            AppConfig(
                api = AppConfig.Api(signingSecret = signingSecret, requestTimestampToleranceSeconds = 300),
            )

        fun filter(
            signingSecret: String = SIGNING_SECRET,
            profiles: Array<String> = arrayOf("prod"),
            meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
            retryDeduplicator: SlackRetryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock),
        ) = SlackRequestVerificationFilter(
            appConfig = appConfig(signingSecret = signingSecret),
            environment = MockEnvironment().apply { setActiveProfiles(*profiles) },
            signatureVerifier = signatureVerifier,
            retryDeduplicator = retryDeduplicator,
            meterRegistry = meterRegistry,
        )

        fun signed(
            rawBody: String = EVENT_BODY,
            path: String = EVENTS_PATH,
            timestamp: String = TIMESTAMP,
            retryNum: String? = null,
        ) = slackRequest(
            rawBody = rawBody,
            path = path,
            timestamp = timestamp,
            signature =
                signatureVerifier.createSignature(
                    signingSecret = SIGNING_SECRET,
                    requestTimestamp = timestamp,
                    body = rawBody.toByteArray(Charsets.UTF_8),
                ),
            retryNum = retryNum,
        )

        given("a signed request") {
            `when`("a slash command form request is valid") {
                val rawBody = "team_id=T123&command=%2Fmeetup&text=list"
                val response = MockHttpServletResponse()
                val chain = CountingFilterChain()

                filter().doFilter(signed(rawBody = rawBody, path = "/api/slash/meet"), response, chain)

                then("the request continues with a cached body wrapper") {
                    response.status shouldBe 200
                    chain.invocationCount shouldBe 1
                    (chain.lastRequest is CachedBodyHttpServletRequest) shouldBe true
                    chain.lastRequest?.getParameter("command") shouldBe "/meetup"
                }
            }

            `when`("the Slack signature is invalid") {
                val response = MockHttpServletResponse()
                val chain = CountingFilterChain()

                filter().doFilter(
                    slackRequest(rawBody = EVENT_BODY, path = EVENTS_PATH, timestamp = TIMESTAMP, signature = "v0=x"),
                    response,
                    chain,
                )

                then("the request is rejected") {
                    response.status shouldBe 401
                    chain.invocationCount shouldBe 0
                }
            }

            `when`("the declared Content-Length exceeds the limit") {
                val request =
                    object : MockHttpServletRequest() {
                        override fun getContentLengthLong(): Long =
                            CachedBodyHttpServletRequest.MAX_BODY_BYTES.toLong() + 1
                    }.apply {
                        method = "POST"
                        requestURI = EVENTS_PATH
                        addHeader(SlackHeaders.REQUEST_TIMESTAMP, TIMESTAMP)
                        addHeader(SlackHeaders.SIGNATURE, WELL_FORMED_SIGNATURE)
                        setContent(EVENT_BODY.toByteArray())
                    }
                val response = MockHttpServletResponse()
                val chain = CountingFilterChain()

                filter().doFilter(request, response, chain)

                then("it is rejected with 413 before signature verification") {
                    response.status shouldBe 413
                    chain.invocationCount shouldBe 0
                }
            }

            `when`("a signed form body is replayed with a forged query string") {
                val rawBody = "team_id=T123&user_id=U_REAL&payload=%7B%7D"
                val request =
                    signed(rawBody = rawBody, path = "/api/slash/meet").apply {
                        queryString = "payload=forged&user_id=U_ADMIN&extra=1"
                        addParameter("payload", "forged")
                        addParameter("user_id", "U_ADMIN")
                        addParameter("extra", "1")
                    }
                val chain = CountingFilterChain()

                filter().doFilter(request, MockHttpServletResponse(), chain)

                then("the handlers only see parameters from the signed body") {
                    val forwarded = chain.lastRequest as HttpServletRequest
                    forwarded.getParameter("user_id") shouldBe "U_REAL"
                    forwarded.getParameterValues("payload")?.toList() shouldBe listOf("{}")
                    forwarded.parameterMap.keys shouldBe setOf("team_id", "user_id", "payload")
                    forwarded.parameterNames.toList() shouldBe listOf("team_id", "user_id", "payload")
                    forwarded.getParameter("extra") shouldBe null
                    forwarded.queryString shouldBe null
                }
            }
        }

        given("a request whose Slack headers are unusable") {
            listOf(
                "missing headers" to (null to null),
                "a non-numeric timestamp" to ("soon" to WELL_FORMED_SIGNATURE),
                "a stale timestamp" to ((TIMESTAMP.toLong() - 301L).toString() to WELL_FORMED_SIGNATURE),
                "a signature without the v0 prefix" to (TIMESTAMP to WELL_FORMED_SIGNATURE.removePrefix("v0=")),
                "a signature that is not 64 hex digits" to (TIMESTAMP to "v0=abc"),
                "an upper-case hex signature" to (TIMESTAMP to "v0=" + "A".repeat(64)),
            ).forEach { (case, headers) ->
                `when`("the request carries $case") {
                    val request = BodyReadTrackingRequest()
                    request.method = "POST"
                    request.requestURI = EVENTS_PATH
                    headers.first?.let { request.addHeader(SlackHeaders.REQUEST_TIMESTAMP, it) }
                    headers.second?.let { request.addHeader(SlackHeaders.SIGNATURE, it) }
                    request.setContent(EVENT_BODY.toByteArray(Charsets.UTF_8))
                    val response = MockHttpServletResponse()
                    val chain = CountingFilterChain()

                    filter().doFilter(request, response, chain)

                    then("it is rejected with 401 without reading the body") {
                        response.status shouldBe 401
                        chain.invocationCount shouldBe 0
                        request.bodyRead shouldBe false
                    }
                }
            }
        }

        given("a request rejected for unusable Slack headers") {
            `when`("the filter logs the rejection") {
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                rootLogger.addAppender(appender)
                try {
                    filter().doFilter(
                        unsignedRequest(requestUri = EVENTS_PATH),
                        MockHttpServletResponse(),
                        CountingFilterChain(),
                    )
                } finally {
                    rootLogger.detachAppender(appender)
                }
                val messages = appender.list.map { it.formattedMessage }

                then("the log line carries the rejection reason, not a lambda object") {
                    messages.any { it.contains("Rejected Slack request headers") } shouldBe true
                    messages.none { it.contains("Lambda") } shouldBe true
                }
            }
        }

        given("Slack retries on the Events API path") {
            `when`("the original attempt completed") {
                val underTest = filter()
                val firstChain = CountingFilterChain()
                underTest.doFilter(signed(), MockHttpServletResponse(), firstChain)

                val retryResponse = MockHttpServletResponse()
                val retryChain = CountingFilterChain()
                underTest.doFilter(
                    signed(timestamp = (TIMESTAMP.toLong() + 60L).toString(), retryNum = "1"),
                    retryResponse,
                    retryChain,
                )

                then("the retry is acknowledged with 200 without invoking the handlers") {
                    firstChain.invocationCount shouldBe 1
                    retryResponse.status shouldBe 200
                    retryChain.invocationCount shouldBe 0
                }
            }

            `when`("a captured signed request is replayed without a retry number") {
                val underTest = filter()
                val firstChain = CountingFilterChain()
                underTest.doFilter(signed(), MockHttpServletResponse(), firstChain)

                val replayResponse = MockHttpServletResponse()
                val replayChain = CountingFilterChain()
                underTest.doFilter(signed(), replayResponse, replayChain)

                then("the replay is acknowledged with 200 and the handlers run only once") {
                    firstChain.invocationCount shouldBe 1
                    replayResponse.status shouldBe 200
                    replayChain.invocationCount shouldBe 0
                }
            }

            `when`("the retry arrives while the original attempt is still running") {
                val meterRegistry = SimpleMeterRegistry()
                val underTest = filter(meterRegistry = meterRegistry)
                val retryResponse = MockHttpServletResponse()
                val retryChain = CountingFilterChain()
                val originalChain =
                    CountingFilterChain(
                        onInvoke = { underTest.doFilter(signed(retryNum = "1"), retryResponse, retryChain) },
                    )

                underTest.doFilter(signed(), MockHttpServletResponse(), originalChain)

                then("the retry gets 503 so Slack keeps retrying, and the handlers run only once") {
                    originalChain.invocationCount shouldBe 1
                    retryResponse.status shouldBe 503
                    retryChain.invocationCount shouldBe 0
                }

                then("the deferred retry is counted") {
                    meterRegistry
                        .counter(SlackRequestVerificationFilter.METRIC_DEFERRED_RETRIES)
                        .count() shouldBe 1.0
                }
            }

            `when`("a different event arrives while the deduplicator is full of in-flight entries") {
                val underTest =
                    filter(retryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock, maxEntries = 1))
                val otherBody = """{"event_id":"Ev999","type":"event_callback"}"""
                val otherChain = CountingFilterChain()
                val originalChain =
                    CountingFilterChain(
                        onInvoke = {
                            underTest.doFilter(signed(rawBody = otherBody), MockHttpServletResponse(), otherChain)
                        },
                    )

                underTest.doFilter(signed(), MockHttpServletResponse(), originalChain)

                then("the signed event is still processed, just untracked") {
                    originalChain.invocationCount shouldBe 1
                    otherChain.invocationCount shouldBe 1
                }
            }

            `when`("the original attempt answered 5xx") {
                val underTest = filter()
                val failingResponse = MockHttpServletResponse()
                underTest.doFilter(signed(), failingResponse, CountingFilterChain(statusToSet = 500))

                val retryChain = CountingFilterChain()
                underTest.doFilter(signed(retryNum = "1"), MockHttpServletResponse(), retryChain)

                then("the retry reaches the handlers") {
                    failingResponse.status shouldBe 500
                    retryChain.invocationCount shouldBe 1
                }
            }

            `when`("the original attempt threw an Error") {
                val underTest = filter()
                shouldThrow<StackOverflowError> {
                    underTest.doFilter(
                        signed(),
                        MockHttpServletResponse(),
                        CountingFilterChain(onInvoke = { throw StackOverflowError() }),
                    )
                }

                val retryChain = CountingFilterChain()
                underTest.doFilter(signed(retryNum = "1"), MockHttpServletResponse(), retryChain)

                then("the attempt is forgotten and the retry reaches the handlers") {
                    retryChain.invocationCount shouldBe 1
                }
            }

            `when`("a slash command body repeats with a retry number") {
                val underTest = filter()
                val rawBody = "team_id=T123&command=%2Fmeetup&text=list"
                val firstChain = CountingFilterChain()
                val secondChain = CountingFilterChain()
                underTest.doFilter(
                    signed(rawBody = rawBody, path = "/api/slash/meet"),
                    MockHttpServletResponse(),
                    firstChain,
                )
                underTest.doFilter(
                    signed(rawBody = rawBody, path = "/api/slash/meet", retryNum = "1"),
                    MockHttpServletResponse(),
                    secondChain,
                )

                then("slash paths are not deduplicated because Slack never retries them") {
                    firstChain.invocationCount shouldBe 1
                    secondChain.invocationCount shouldBe 1
                }
            }
        }

        given("an unsigned request whose raw URI hides a Slack path") {
            listOf(
                "/api/sla%63k/events",
                "/api/x/../slack/events",
                "/api/slack/./events",
                "/api/slack/events;jsessionid=abc",
                "/api/slack/events/",
                "/API/Slack/Events",
                "//api/slack/events",
                "/api%2Fslack/events",
                "/api/sla%73h/meet",
                "/api/slack/%69nteraction",
            ).forEach { rawUri ->
                `when`("the raw URI is $rawUri") {
                    val response = MockHttpServletResponse()
                    val chain = CountingFilterChain()

                    filter().doFilter(unsignedRequest(requestUri = rawUri), response, chain)

                    then("the filter still verifies it and rejects it") {
                        response.status shouldBe 401
                        chain.invocationCount shouldBe 0
                    }
                }
            }

            `when`("only the container-decoded servlet path names the Slack endpoint") {
                val request =
                    unsignedRequest(requestUri = "/api/opaque").apply { servletPath = EVENTS_PATH }
                val response = MockHttpServletResponse()
                val chain = CountingFilterChain()

                filter().doFilter(request, response, chain)

                then("the decoded path decides and the request is rejected") {
                    response.status shouldBe 401
                    chain.invocationCount shouldBe 0
                }
            }

            `when`("the path is outside the Slack controllers") {
                val response = MockHttpServletResponse()
                val chain = CountingFilterChain()

                filter().doFilter(unsignedRequest(requestUri = "/actuator/health"), response, chain)

                then("the filter does not apply") {
                    chain.invocationCount shouldBe 1
                }
            }
        }

        given("the signing secret policy") {
            `when`("a real secret is configured") {
                then("the filter is created") {
                    filter(signingSecret = SIGNING_SECRET, profiles = arrayOf("prod"))
                }
            }

            `when`("the secret is blank outside the local profile") {
                then("startup fails") {
                    shouldThrow<IllegalStateException> { filter(signingSecret = " ", profiles = arrayOf("dev")) }
                        .message shouldContain "blank"
                }
            }

            listOf(
                arrayOf("slack-live", "local"),
                arrayOf("prod", "local"),
                arrayOf("local", "dev"),
            ).forEach { profiles ->
                `when`("the secret is blank and local is combined with ${profiles.joinToString()}") {
                    then("startup fails and names the active profiles") {
                        val message =
                            shouldThrow<IllegalStateException> { filter(signingSecret = "", profiles = profiles) }
                                .message
                                .orEmpty()
                        profiles.forEach { profile -> message shouldContain profile }
                    }
                }
            }

            `when`("the secret is blank and no profile is active") {
                then("startup fails") {
                    shouldThrow<IllegalStateException> { filter(signingSecret = "", profiles = emptyArray()) }
                }
            }

            `when`("the secret is an unresolved placeholder") {
                then("startup fails even under the local profile") {
                    shouldThrow<IllegalStateException> {
                        filter(signingSecret = "\${SLACK_SIGNING_SECRET}", profiles = arrayOf("local"))
                    }.message shouldContain "placeholder"
                }
            }

            `when`("the secret is blank under the local profile") {
                val response = MockHttpServletResponse()
                val chain = CountingFilterChain()

                filter(signingSecret = "", profiles = arrayOf("local"))
                    .doFilter(unsignedRequest(requestUri = EVENTS_PATH), response, chain)

                then("verification is skipped") {
                    chain.invocationCount shouldBe 1
                    response.status shouldBe 200
                }
            }

            `when`("application-local.yaml, the only profile allowed to run unverified, is loaded") {
                val localProfile = YamlPropertySourceLoader().load("local", ClassPathResource("application-local.yaml"))
                val environment = MockEnvironment().apply { localProfile.forEach { propertySources.addLast(it) } }

                then("the HTTP server binds to loopback only") {
                    environment.getProperty("server.address") shouldBe "127.0.0.1"
                }
            }
        }
    })

private fun slackRequest(
    rawBody: String,
    path: String,
    timestamp: String,
    signature: String,
    retryNum: String? = null,
): MockHttpServletRequest =
    MockHttpServletRequest().apply {
        method = "POST"
        requestURI = path
        contentType =
            if (path.startsWith("/api/slash")) {
                MediaType.APPLICATION_FORM_URLENCODED_VALUE
            } else {
                MediaType.APPLICATION_JSON_VALUE
            }
        characterEncoding = Charsets.UTF_8.name()
        addHeader(SlackHeaders.REQUEST_TIMESTAMP, timestamp)
        addHeader(SlackHeaders.SIGNATURE, signature)
        retryNum?.let { addHeader(SlackHeaders.RETRY_NUM, it) }
        setContent(rawBody.toByteArray(Charsets.UTF_8))
    }

private fun unsignedRequest(requestUri: String): MockHttpServletRequest =
    MockHttpServletRequest().apply {
        method = "POST"
        requestURI = requestUri
        contentType = MediaType.APPLICATION_JSON_VALUE
        setContent(EVENT_BODY.toByteArray(Charsets.UTF_8))
    }

private class CountingFilterChain(
    private val statusToSet: Int? = null,
    private val onInvoke: () -> Unit = {},
) : FilterChain {
    var invocationCount = 0
        private set
    var lastRequest: ServletRequest? = null
        private set

    override fun doFilter(request: ServletRequest, response: ServletResponse) {
        invocationCount += 1
        lastRequest = request
        onInvoke()
        statusToSet?.let { (response as HttpServletResponse).status = it }
    }
}

private class BodyReadTrackingRequest : MockHttpServletRequest() {
    var bodyRead = false
        private set

    override fun getInputStream(): ServletInputStream {
        bodyRead = true
        return super.getInputStream()
    }
}
