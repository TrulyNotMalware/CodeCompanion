package dev.notypie.impl.cve

import dev.notypie.common.jsonMapper
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.schema.CveSourceType
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

private val log = KotlinLogging.logger {}

class GithubReleaseSourceAdapter(
    private val token: String,
    private val perPage: Int,
    private val requestTimeout: Duration,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) : SourceAdapter {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    override fun supports(sourceType: CveSourceType): Boolean = sourceType == CveSourceType.GITHUB_RELEASE

    fun anonymousLimitWarning(topicCount: Int, requestsPerTopicPerHour: Long): String? {
        if (token.isNotBlank()) return null
        val hourly = topicCount * requestsPerTopicPerHour
        if (hourly < ANONYMOUS_HOURLY_LIMIT) return null
        return "slack.app.cve.github.token is blank: $topicCount GitHub topic(s) need ~$hourly requests/hour " +
            "against GitHub's anonymous limit of $ANONYMOUS_HOURLY_LIMIT; set GITHUB_TOKEN or collection will be " +
            "rate limited (403) and those windows come back empty"
    }

    override fun fetch(topic: CveTopic): List<RawSourceEvent> {
        val repo = parseRepo(topic = topic) ?: return emptyList()
        val request =
            HttpRequest
                .newBuilder(URI.create("$apiBaseUrl/repos/$repo/releases?per_page=$perPage"))
                .timeout(requestTimeout)
                .header("Accept", "application/vnd.github+json")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .GET()
                .build()

        val response =
            runCatching {
                httpClient.sendWithinDeadline(request = request, deadline = requestTimeout, maxBodyBytes = maxBodyBytes)
            }.getOrElse { ex ->
                if (ex is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ex
                }
                log.warn(ex) { "GitHub releases request failed for topic=${topic.topicKey}" }
                return emptyList()
            }
        if (response.statusCode !in 200..299) {
            logFailure(response = response, topic = topic)
            return emptyList()
        }
        return parseReleases(body = response.body, topic = topic)
    }

    private fun logFailure(response: SourceResponse, topic: CveTopic) {
        val status = response.statusCode
        val remaining = response.headers.firstValue("x-ratelimit-remaining").getOrNull()
        val retryAfter = response.headers.firstValue("retry-after").getOrNull()
        if (status !in RATE_LIMIT_STATUSES || (remaining != "0" && retryAfter == null)) {
            log.warn { "GitHub releases returned $status for topic=${topic.topicKey}" }
            return
        }
        val resetAt =
            response.headers
                .firstValue("x-ratelimit-reset")
                .getOrNull()
                ?.toLongOrNull()
                ?.let { Instant.ofEpochSecond(it) }
        val auth = if (token.isBlank()) "anonymous, $ANONYMOUS_HOURLY_LIMIT requests/hour" else "token"
        log.warn {
            "GitHub rate limit exhausted for topic=${topic.topicKey} (status=$status, $auth, " +
                "resets at ${resetAt ?: "unknown"}, retry-after=${retryAfter ?: "-"}); this window is skipped"
        }
    }

    private fun parseRepo(topic: CveTopic): String? {
        val config = topic.sourceConfig
        if (config.isNullOrBlank()) {
            log.error { "GitHub topic=${topic.topicKey} has no source_config" }
            return null
        }
        val repo =
            runCatching { jsonMapper.readTree(config)["repo"]?.stringOrNull() }
                .getOrElse { ex ->
                    log.error(ex) { "GitHub topic=${topic.topicKey} source_config is not valid JSON" }
                    return null
                }
        if (repo.isNullOrBlank()) {
            log.error { "GitHub topic=${topic.topicKey} source_config missing 'repo'" }
            return null
        }
        if (!REPO_PATTERN.matches(repo)) {
            log.error { "GitHub topic=${topic.topicKey} source_config 'repo' is not owner/name shaped" }
            return null
        }
        return repo
    }

    private fun parseReleases(body: String, topic: CveTopic): List<RawSourceEvent> {
        val root =
            runCatching { jsonMapper.readTree(body) }
                .getOrElse { ex ->
                    log.warn(ex) { "GitHub releases response was not valid JSON for topic=${topic.topicKey}" }
                    return emptyList()
                }
        if (!root.isArray) {
            log.warn { "GitHub releases response was not a JSON array for topic=${topic.topicKey}" }
            return emptyList()
        }
        return root.mapNotNull { toRawEvent(node = it) }
    }

    private fun toRawEvent(node: JsonNode): RawSourceEvent? {
        val externalId = node["id"]?.stringOrNull() ?: return null
        val title = node["name"]?.stringOrNull() ?: node["tag_name"]?.stringOrNull() ?: return null
        return RawSourceEvent(
            externalId = externalId,
            title = title,
            rawContent = node["body"]?.stringOrNull() ?: "",
            publishedAt = parseSourceTimestamp(value = node["published_at"]?.stringOrNull()),
        )
    }

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.github.com"
        const val DEFAULT_MAX_BODY_BYTES = 8 * 1024 * 1024
        const val ANONYMOUS_HOURLY_LIMIT = 60L
        private val RATE_LIMIT_STATUSES = setOf(403, 429)

        private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    }
}
