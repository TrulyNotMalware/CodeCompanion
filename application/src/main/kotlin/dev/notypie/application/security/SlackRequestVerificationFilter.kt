package dev.notypie.application.security

import dev.notypie.application.configurations.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.server.PathContainer
import org.springframework.http.server.RequestPath
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SlackRequestVerificationFilter(
    private val appConfig: AppConfig,
    environment: Environment,
    private val signatureVerifier: SlackSignatureVerifier,
    private val retryDeduplicator: SlackRetryDeduplicator,
    meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
    private val verificationDisabledLogged = AtomicBoolean(false)
    private val deferredRetries = meterRegistry.counter(METRIC_DEFERRED_RETRIES)

    init {
        requireUsableSigningSecret(
            signingSecret = appConfig.api.signingSecret,
            activeProfiles = environment.activeProfiles.toSet(),
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        candidatePaths(request = request).none { path -> isSlackPath(path = path) }

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

        val requestPath = normalizedPath(request = request)
        val headerCheck =
            signatureVerifier.checkHeaders(
                requestTimestamp = request.getHeader(SlackHeaders.REQUEST_TIMESTAMP),
                requestSignature = request.getHeader(SlackHeaders.SIGNATURE),
                toleranceSeconds = appConfig.api.requestTimestampToleranceSeconds,
            )
        if (!headerCheck.valid) {
            logger.warn { "Rejected Slack request headers: reason=${headerCheck.reason} path=$requestPath" }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }

        val cachedRequest =
            CachedBodyHttpServletRequest.cacheWithinLimit(request = request)
                ?: run {
                    logger.warn { "Rejected oversized Slack request: path=$requestPath" }
                    response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
                    return
                }
        val verification =
            signatureVerifier.verify(
                signingSecret = signingSecret,
                requestTimestamp = cachedRequest.getHeader(SlackHeaders.REQUEST_TIMESTAMP),
                requestSignature = cachedRequest.getHeader(SlackHeaders.SIGNATURE),
                body = cachedRequest.body,
                toleranceSeconds = appConfig.api.requestTimestampToleranceSeconds,
            )

        if (!verification.valid) {
            logger.warn { "Rejected Slack request: reason=${verification.reason} path=$requestPath" }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }

        if (requestPath != RETRYABLE_EVENTS_PATH) {
            filterChain.doFilter(cachedRequest, response)
            return
        }

        val fingerprint =
            SlackRequestFingerprint.of(
                method = cachedRequest.method,
                requestPath = requestPath,
                body = cachedRequest.body,
            )
        val retryNum = cachedRequest.getHeader(SlackHeaders.RETRY_NUM)
        when (val admission = retryDeduplicator.admit(fingerprint = fingerprint, retryNum = retryNum)) {
            SlackRetryAdmission.RetryOfCompleted -> {
                logger.info { "Acknowledged Slack retry of a completed request: retryNum=$retryNum" }
                response.status = HttpServletResponse.SC_OK
            }

            SlackRetryAdmission.RetryOfInFlight -> {
                logger.info { "Deferred Slack retry of an in-flight request: retryNum=$retryNum" }
                deferredRetries.increment()
                response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            }

            SlackRetryAdmission.Untracked -> {
                filterChain.doFilter(cachedRequest, response)
            }

            is SlackRetryAdmission.FirstAttempt -> {
                runTracked(
                    ticket = admission.ticket,
                    request = cachedRequest,
                    response = response,
                    filterChain = filterChain,
                )
            }
        }
    }

    private fun runTracked(
        ticket: SlackRetryTicket,
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } catch (failure: Throwable) {
            retryDeduplicator.markFailed(ticket = ticket)
            throw failure
        }
        if (response.status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
            retryDeduplicator.markFailed(ticket = ticket)
        } else {
            retryDeduplicator.markCompleted(ticket = ticket)
        }
    }

    companion object {
        internal const val METRIC_DEFERRED_RETRIES = "codecompanion.slack.retry.deferred"
        private const val LOCAL_PROFILE = "local"
        private const val RETRYABLE_EVENTS_PATH = "/api/slack/events"
        private val SLACK_PATH_PREFIXES = listOf("/api/slack", "/api/slash")
        private val UNRESOLVED_PLACEHOLDER = Regex("""\$\{[^}]*}""")
        private val REPEATED_SLASHES = Regex("/{2,}")
        private val CONTROL_CHARACTERS = Regex("\\p{Cntrl}")

        private fun requireUsableSigningSecret(signingSecret: String, activeProfiles: Set<String>) {
            check(!UNRESOLVED_PLACEHOLDER.containsMatchIn(signingSecret)) {
                "slack.app.api.signing-secret is an unresolved placeholder; set SLACK_SIGNING_SECRET"
            }
            check(signingSecret.isNotBlank() || activeProfiles == setOf(LOCAL_PROFILE)) {
                "slack.app.api.signing-secret is blank; only the '$LOCAL_PROFILE' profile on its own may run " +
                    "without Slack signature verification (active profiles: $activeProfiles)"
            }
        }

        private fun normalizedPath(request: HttpServletRequest): String =
            normalize(path = dispatcherPath(request = request))

        private fun candidatePaths(request: HttpServletRequest): List<String> =
            listOf(
                request.requestURI.orEmpty(),
                request.servletPath.orEmpty() + request.pathInfo.orEmpty(),
                dispatcherPath(request = request),
            ).map { path -> normalize(path = path) }

        private fun dispatcherPath(request: HttpServletRequest): String =
            runCatching {
                RequestPath
                    .parse(request.requestURI.orEmpty(), request.contextPath)
                    .pathWithinApplication()
                    .elements()
                    .joinToString(separator = "") { element ->
                        if (element is PathContainer.PathSegment) element.valueToMatch() else element.value()
                    }
            }.getOrElse { request.requestURI.orEmpty() }

        private fun normalize(path: String): String {
            val collapsed =
                path
                    .replace(regex = CONTROL_CHARACTERS, replacement = "")
                    .replace(regex = REPEATED_SLASHES, replacement = "/")
            val cleaned = StringUtils.cleanPath(collapsed).lowercase()
            return if (cleaned.length > 1) cleaned.trimEnd('/') else cleaned
        }

        private fun isSlackPath(path: String): Boolean =
            SLACK_PATH_PREFIXES.any { prefix -> path == prefix || path.startsWith("$prefix/") }
    }
}

object SlackHeaders {
    const val SIGNATURE = "X-Slack-Signature"
    const val REQUEST_TIMESTAMP = "X-Slack-Request-Timestamp"
    const val RETRY_NUM = "X-Slack-Retry-Num"
    const val NO_RETRY = "X-Slack-No-Retry"
}
