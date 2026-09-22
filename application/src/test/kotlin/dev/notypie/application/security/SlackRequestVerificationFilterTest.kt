package dev.notypie.application.security

import dev.notypie.application.configurations.AppConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SlackRequestVerificationFilterTest :
    BehaviorSpec({
        given("SlackRequestVerificationFilter") {
            val signingSecret = "secret"
            val timestamp = "1714280000"
            val clock = Clock.fixed(Instant.ofEpochSecond(timestamp.toLong()), ZoneOffset.UTC)
            val signatureVerifier = SlackSignatureVerifier(clock = clock)
            val appConfig =
                AppConfig(
                    api =
                        AppConfig.Api(
                            signingSecret = signingSecret,
                            requestTimestampToleranceSeconds = 300,
                        ),
                )

            `when`("a signed Slack form request is valid") {
                val rawBody = "team_id=T123&command=%2Fmeetup&text=list"
                val request =
                    signedRequest(
                        rawBody = rawBody,
                        timestamp = timestamp,
                        signature =
                            signatureVerifier.createSignature(
                                signingSecret = signingSecret,
                                requestTimestamp = timestamp,
                                body = rawBody.toByteArray(Charsets.UTF_8),
                            ),
                    )
                val response = MockHttpServletResponse()
                val filterChain = CountingFilterChain()
                val filter =
                    SlackRequestVerificationFilter(
                        appConfig = appConfig,
                        signatureVerifier = signatureVerifier,
                        retryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock),
                    )

                filter.doFilter(request, response, filterChain)

                then("the request should continue with a cached body wrapper") {
                    response.status shouldBe 200
                    filterChain.invocationCount shouldBe 1
                    (filterChain.lastRequest is CachedBodyHttpServletRequest) shouldBe true
                    filterChain.lastRequest?.getParameter("command") shouldBe "/meetup"
                }
            }

            `when`("a Slack retry repeats a request already seen") {
                val rawBody = "team_id=T123&command=%2Fmeetup&text=list"
                val signature =
                    signatureVerifier.createSignature(
                        signingSecret = signingSecret,
                        requestTimestamp = timestamp,
                        body = rawBody.toByteArray(Charsets.UTF_8),
                    )
                val filter =
                    SlackRequestVerificationFilter(
                        appConfig = appConfig,
                        signatureVerifier = signatureVerifier,
                        retryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock),
                    )

                val firstResponse = MockHttpServletResponse()
                val firstChain = CountingFilterChain()
                filter.doFilter(
                    signedRequest(rawBody = rawBody, timestamp = timestamp, signature = signature),
                    firstResponse,
                    firstChain,
                )

                val retryResponse = MockHttpServletResponse()
                val retryChain = CountingFilterChain()
                filter.doFilter(
                    signedRequest(
                        rawBody = rawBody,
                        timestamp = timestamp,
                        signature = signature,
                        retryNum = "1",
                    ),
                    retryResponse,
                    retryChain,
                )

                then("the retry should be acknowledged without invoking downstream handlers") {
                    firstChain.invocationCount shouldBe 1
                    retryResponse.status shouldBe 200
                    retryChain.invocationCount shouldBe 0
                }
            }

            `when`("a Slack retry carries a fresh timestamp and signature, as real retries do") {
                val rawBody = "team_id=T123&command=%2Fmeetup&text=list"
                val filter =
                    SlackRequestVerificationFilter(
                        appConfig = appConfig,
                        signatureVerifier = signatureVerifier,
                        retryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock),
                    )

                fun signed(timestamp: String, retryNum: String? = null) =
                    signedRequest(
                        rawBody = rawBody,
                        timestamp = timestamp,
                        signature =
                            signatureVerifier.createSignature(
                                signingSecret = signingSecret,
                                requestTimestamp = timestamp,
                                body = rawBody.toByteArray(Charsets.UTF_8),
                            ),
                        retryNum = retryNum,
                    )

                val firstChain = CountingFilterChain()
                filter.doFilter(signed(timestamp = timestamp), MockHttpServletResponse(), firstChain)

                val retryResponse = MockHttpServletResponse()
                val retryChain = CountingFilterChain()
                val laterTimestamp = (timestamp.toLong() + 60L).toString()
                filter.doFilter(signed(timestamp = laterTimestamp, retryNum = "1"), retryResponse, retryChain)

                then("the retry is still recognised because the fingerprint keys on the body") {
                    firstChain.invocationCount shouldBe 1
                    retryResponse.status shouldBe 200
                    retryChain.invocationCount shouldBe 0
                }
            }

            `when`("the first attempt failed with a 5xx and Slack retries it") {
                val rawBody = "team_id=T123&command=%2Fmeetup&text=list"
                val signature =
                    signatureVerifier.createSignature(
                        signingSecret = signingSecret,
                        requestTimestamp = timestamp,
                        body = rawBody.toByteArray(Charsets.UTF_8),
                    )
                val filter =
                    SlackRequestVerificationFilter(
                        appConfig = appConfig,
                        signatureVerifier = signatureVerifier,
                        retryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock),
                    )

                val failingResponse = MockHttpServletResponse()
                filter.doFilter(
                    signedRequest(rawBody = rawBody, timestamp = timestamp, signature = signature),
                    failingResponse,
                    CountingFilterChain(statusToSet = 500),
                )

                val retryChain = CountingFilterChain()
                filter.doFilter(
                    signedRequest(rawBody = rawBody, timestamp = timestamp, signature = signature, retryNum = "1"),
                    MockHttpServletResponse(),
                    retryChain,
                )

                then("the retry reaches the handlers instead of being acknowledged away") {
                    failingResponse.status shouldBe 500
                    retryChain.invocationCount shouldBe 1
                }
            }

            `when`("the Slack signature is invalid") {
                val request =
                    signedRequest(
                        rawBody = "team_id=T123",
                        timestamp = timestamp,
                        signature = "v0=invalid",
                    )
                val response = MockHttpServletResponse()
                val filterChain = CountingFilterChain()
                val filter =
                    SlackRequestVerificationFilter(
                        appConfig = appConfig,
                        signatureVerifier = signatureVerifier,
                        retryDeduplicator = InMemorySlackRetryDeduplicator(clock = clock),
                    )

                filter.doFilter(request, response, filterChain)

                then("the request should be rejected") {
                    response.status shouldBe 401
                    filterChain.invocationCount shouldBe 0
                }
            }
        }
    })

private fun signedRequest(
    rawBody: String,
    timestamp: String,
    signature: String,
    retryNum: String? = null,
): MockHttpServletRequest =
    MockHttpServletRequest().apply {
        method = "POST"
        requestURI = "/api/slash/meet"
        contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
        characterEncoding = Charsets.UTF_8.name()
        addHeader(SlackHeaders.REQUEST_TIMESTAMP, timestamp)
        addHeader(SlackHeaders.SIGNATURE, signature)
        retryNum?.let { addHeader(SlackHeaders.RETRY_NUM, it) }
        setContent(rawBody.toByteArray(Charsets.UTF_8))
    }

private class CountingFilterChain(
    private val statusToSet: Int? = null,
) : FilterChain {
    var invocationCount = 0
        private set
    var lastRequest: ServletRequest? = null
        private set

    override fun doFilter(request: ServletRequest, response: ServletResponse) {
        invocationCount += 1
        lastRequest = request
        statusToSet?.let { (response as HttpServletResponse).status = it }
    }
}
