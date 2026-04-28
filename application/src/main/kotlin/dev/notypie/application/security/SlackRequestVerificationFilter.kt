package dev.notypie.application.security

import dev.notypie.application.configurations.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SlackRequestVerificationFilter(
    private val appConfig: AppConfig,
    private val signatureVerifier: SlackSignatureVerifier,
    private val retryDeduplicator: SlackRetryDeduplicator,
) : OncePerRequestFilter() {
    private val verificationDisabledLogged = AtomicBoolean(false)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !SLACK_REQUEST_PATHS.any { request.requestURI.startsWith(it) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val signingSecret = appConfig.api.signingSecret
        if (signingSecret.isBlank()) {
            if (verificationDisabledLogged.compareAndSet(false, true)) {
                logger.warn { "Slack request signature verification is disabled because signingSecret is blank" }
            }
            filterChain.doFilter(request, response)
            return
        }

        val cachedRequest = CachedBodyHttpServletRequest(request = request)
        val verification =
            signatureVerifier.verify(
                signingSecret = signingSecret,
                requestTimestamp = cachedRequest.getHeader(SlackHeaders.REQUEST_TIMESTAMP),
                requestSignature = cachedRequest.getHeader(SlackHeaders.SIGNATURE),
                body = cachedRequest.body,
                toleranceSeconds = appConfig.api.requestTimestampToleranceSeconds,
            )

        if (!verification.valid) {
            logger.warn { "Rejected Slack request: reason=${verification.reason} uri=${request.requestURI}" }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }

        val fingerprint =
            SlackRequestFingerprint(
                method = cachedRequest.method,
                requestUri = cachedRequest.requestURI,
                timestamp = cachedRequest.getHeader(SlackHeaders.REQUEST_TIMESTAMP).orEmpty(),
                signature = cachedRequest.getHeader(SlackHeaders.SIGNATURE).orEmpty(),
            )
        val retryNum = cachedRequest.getHeader(SlackHeaders.RETRY_NUM)
        if (retryDeduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = retryNum)) {
            logger.info { "Acknowledged duplicate Slack retry: uri=${request.requestURI} retryNum=$retryNum" }
            response.status = HttpServletResponse.SC_OK
            return
        }

        filterChain.doFilter(cachedRequest, response)
    }

    companion object {
        private val SLACK_REQUEST_PATHS =
            listOf(
                "/api/slash/",
                "/api/slack/events",
                "/api/slack/interaction",
            )
    }
}

object SlackHeaders {
    const val SIGNATURE = "X-Slack-Signature"
    const val REQUEST_TIMESTAMP = "X-Slack-Request-Timestamp"
    const val RETRY_NUM = "X-Slack-Retry-Num"
}
