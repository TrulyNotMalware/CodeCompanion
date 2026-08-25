package dev.notypie.impl.cve

import dev.notypie.common.jsonMapper
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.schema.CveSourceType
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Fetches recent GitHub releases for a topic. `source_config` is `{"repo": "owner/name"}`; the
 * request is `GET /repos/{repo}/releases?per_page={perPage}` with the `application/vnd.github+json`
 * Accept header and, when a [token] is configured, a bearer credential. A malformed config, a
 * non-2xx response, or a transport failure logs and returns an empty list so a single bad topic
 * never breaks the collection tick — the collector retries next window and `insertIgnore` dedups.
 */
class GithubReleaseSourceAdapter(
    private val token: String,
    private val perPage: Int,
    private val requestTimeout: Duration,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) : SourceAdapter {
    // Pinned to HTTP/1.1 to match the sidecar client — the JDK default (HTTP/2) is unnecessary here
    // and HTTP/1.1 keeps request behaviour uniform across the app's outbound clients.
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    override fun supports(sourceType: CveSourceType): Boolean = sourceType == CveSourceType.GITHUB_RELEASE

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
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
                .getOrElse { ex ->
                    // Never logs the token: only the topic key and the exception surface here.
                    log.warn(ex) { "GitHub releases request failed for topic=${topic.topicKey}" }
                    return emptyList()
                }
        if (response.statusCode() !in 200..299) {
            log.warn { "GitHub releases returned ${response.statusCode()} for topic=${topic.topicKey}" }
            return emptyList()
        }
        return parseReleases(body = response.body(), topic = topic)
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
        // The repo lands in the URL path unencoded; anything outside owner/name shape would either
        // blow up URI.create (breaking fetch's never-throw contract) or reshape the request.
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

        private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    }
}
