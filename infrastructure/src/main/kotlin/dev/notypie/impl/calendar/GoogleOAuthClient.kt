package dev.notypie.impl.calendar

import dev.notypie.common.jsonMapper
import dev.notypie.impl.cve.sendWithinDeadline
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

private val log = KotlinLogging.logger {}

class GoogleOAuthException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class GoogleTokenGrant(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val scopes: Set<String>,
    val subject: String?,
    val email: String?,
) {
    fun grants(scope: String): Boolean = scope in scopes

    override fun toString(): String =
        "GoogleTokenGrant(expiresInSeconds=$expiresInSeconds, scopes=$scopes, subject=$subject, email=$email)"
}

class GoogleOAuthClient(
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
    private val requestTimeout: Duration,
    private val authorizationEndpoint: String = DEFAULT_AUTHORIZATION_ENDPOINT,
    private val tokenEndpoint: String = DEFAULT_TOKEN_ENDPOINT,
    private val revokeEndpoint: String = DEFAULT_REVOKE_ENDPOINT,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {
    companion object {
        const val DEFAULT_AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val DEFAULT_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val DEFAULT_REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
        const val CALENDAR_EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.events"
        const val SCOPES = "$CALENDAR_EVENTS_SCOPE openid email"
        private const val DEFAULT_MAX_BODY_BYTES = 64 * 1024
        private const val ALREADY_INVALID_TOKEN_ERROR = "invalid_token"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
    }

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    fun authorizationUrl(state: String): String =
        authorizationEndpoint +
            "?" +
            formEncode(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to SCOPES,
                "access_type" to "offline",
                "prompt" to "consent",
                "state" to state,
            )

    fun exchangeCode(code: String): GoogleTokenGrant {
        val body =
            postForm(
                endpoint = tokenEndpoint,
                fields =
                    listOf(
                        "code" to code,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "redirect_uri" to redirectUri,
                        "grant_type" to "authorization_code",
                    ),
            )
        val accessToken = body.path("access_token").asString("")
        val refreshToken = body.path("refresh_token").asString("")
        if (accessToken.isBlank()) throw GoogleOAuthException(message = "token response has no access_token")
        if (refreshToken.isBlank()) throw GoogleOAuthException(message = "token response has no refresh_token")
        val claims =
            body
                .path("id_token")
                .asString("")
                .takeIf { it.isNotBlank() }
                ?.let { claimsFromIdToken(idToken = it) }
        return GoogleTokenGrant(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = body.path("expires_in").asLong(0L),
            scopes = grantedScopes(body = body),
            subject = claims?.subject,
            email = claims?.email,
        )
    }

    fun revoke(token: String): Boolean {
        val request =
            HttpRequest
                .newBuilder(URI.create(revokeEndpoint))
                .timeout(requestTimeout)
                .header("Content-Type", FORM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(formEncode("token" to token)))
                .build()
        val response =
            try {
                httpClient.sendWithinDeadline(request = request, deadline = requestTimeout, maxBodyBytes = maxBodyBytes)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            } catch (exception: Exception) {
                log.warn(exception) { "Google token revoke request failed" }
                return false
            }
        return when (response.statusCode) {
            in 200..299 -> true
            400 -> {
                val error = revokeError(body = response.body)
                if (error == ALREADY_INVALID_TOKEN_ERROR) {
                    log.info { "Google token revoke returned 400 $error; the token was already invalid" }
                    true
                } else {
                    log.warn { "Google token revoke rejected: status=400 error=$error" }
                    false
                }
            }

            else -> {
                log.warn { "Google token revoke failed: status=${response.statusCode}" }
                false
            }
        }
    }

    private fun revokeError(body: String): String =
        try {
            jsonMapper.readTree(body).path("error").asString("")
        } catch (exception: JacksonException) {
            log.debug(exception) { "Google token revoke error body is not JSON" }
            ""
        }

    private fun postForm(endpoint: String, fields: List<Pair<String, String>>): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", FORM_CONTENT_TYPE)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(*fields.toTypedArray())))
                .build()
        val response =
            try {
                httpClient.sendWithinDeadline(request = request, deadline = requestTimeout, maxBodyBytes = maxBodyBytes)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            } catch (exception: Exception) {
                throw GoogleOAuthException(message = "Google token request failed", cause = exception)
            }
        val body =
            try {
                jsonMapper.readTree(response.body)
            } catch (exception: JacksonException) {
                throw GoogleOAuthException(
                    message = "Google token response is not JSON",
                    statusCode = response.statusCode,
                    cause = exception,
                )
            }
        if (response.statusCode !in 200..299) {
            val error = body.path("error").asString("unknown")
            throw GoogleOAuthException(
                message = "Google token request rejected: $error",
                statusCode = response.statusCode,
            )
        }
        return body
    }

    private fun grantedScopes(body: JsonNode): Set<String> {
        val scopeNode = body.path("scope")
        if (scopeNode.isMissingNode || scopeNode.isNull) {
            log.warn { "Google token response has no scope field; assuming the requested scopes were granted" }
            return SCOPES.split(" ").toSet()
        }
        return scopeNode
            .asString("")
            .split(" ")
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun claimsFromIdToken(idToken: String): IdTokenClaims? {
        val segments = idToken.split(".")
        if (segments.size != 3) return null
        return try {
            val payload = jsonMapper.readTree(Base64.getUrlDecoder().decode(segments[1]))
            IdTokenClaims(
                subject = payload.path("sub").asString("").takeIf { it.isNotBlank() },
                email = payload.path("email").asString("").takeIf { it.isNotBlank() },
            )
        } catch (exception: IllegalArgumentException) {
            log.debug(exception) { "id_token payload is not base64url" }
            null
        } catch (exception: JacksonException) {
            log.debug(exception) { "id_token payload is not JSON" }
            null
        }
    }

    private data class IdTokenClaims(
        val subject: String?,
        val email: String?,
    )

    private fun formEncode(vararg fields: Pair<String, String>): String =
        fields.joinToString(separator = "&") { (name, value) -> "${encode(value = name)}=${encode(value = value)}" }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace(oldValue = "+", newValue = "%20")
}
