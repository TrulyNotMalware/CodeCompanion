package dev.notypie.impl.cve

import dev.notypie.common.jsonMapper
import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.schema.CveSourceType
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * Fetches recently modified CVEs from the NVD 2.0 API for a topic. `source_config` selects the
 * query: `{"cpe": "cpe:2.3:..."}` maps to `virtualMatchString`, `{"keyword": "..."}` to
 * `keywordSearch`. Every request bounds the scan to `[now - lookback, now]` via
 * `lastModStartDate`/`lastModEndDate`; the adapter is stateless, so overlapping windows are fine —
 * `insertIgnore` dedups them. The `apiKey` header is sent only when configured. A malformed config,
 * a non-2xx response (including rate limits), or a transport failure logs and returns an empty list.
 */
class NvdCveSourceAdapter(
    private val apiKey: String,
    private val lookbackMinutes: Long,
    private val requestTimeout: Duration,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
) : SourceAdapter {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    override fun supports(sourceType: CveSourceType): Boolean = sourceType == CveSourceType.NVD_CVE

    override fun fetch(topic: CveTopic): List<RawSourceEvent> {
        val matchParam = parseMatchParam(topic = topic) ?: return emptyList()
        // NVD reads offset-free timestamps as UTC, so the window is derived from the instant in
        // UTC regardless of the clock's zone — a zoned wall clock would shift the window and
        // silently empty every response.
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val query =
            listOf(
                matchParam,
                "lastModStartDate=${encode(value = now.minusMinutes(lookbackMinutes).format(NVD_DATE_FORMAT))}",
                "lastModEndDate=${encode(value = now.format(NVD_DATE_FORMAT))}",
            ).joinToString(separator = "&")

        val request =
            HttpRequest
                .newBuilder(URI.create("$apiBaseUrl?$query"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .apply { if (apiKey.isNotBlank()) header("apiKey", apiKey) }
                .GET()
                .build()

        val response =
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
                .getOrElse { ex ->
                    // Never logs the api key: only the topic key and the exception surface here.
                    log.warn(ex) { "NVD request failed for topic=${topic.topicKey}" }
                    return emptyList()
                }
        if (response.statusCode() !in 200..299) {
            log.warn { "NVD returned ${response.statusCode()} for topic=${topic.topicKey}" }
            return emptyList()
        }
        return parseVulnerabilities(body = response.body(), topic = topic)
    }

    private fun parseMatchParam(topic: CveTopic): String? {
        val config = topic.sourceConfig
        if (config.isNullOrBlank()) {
            log.error { "NVD topic=${topic.topicKey} has no source_config" }
            return null
        }
        val root =
            runCatching { jsonMapper.readTree(config) }
                .getOrElse { ex ->
                    log.error(ex) { "NVD topic=${topic.topicKey} source_config is not valid JSON" }
                    return null
                }
        val cpe = root["cpe"]?.stringOrNull()
        if (!cpe.isNullOrBlank()) return "virtualMatchString=${encode(value = cpe)}"
        val keyword = root["keyword"]?.stringOrNull()
        if (!keyword.isNullOrBlank()) return "keywordSearch=${encode(value = keyword)}"
        log.error { "NVD topic=${topic.topicKey} source_config needs 'cpe' or 'keyword'" }
        return null
    }

    private fun parseVulnerabilities(body: String, topic: CveTopic): List<RawSourceEvent> {
        val root =
            runCatching { jsonMapper.readTree(body) }
                .getOrElse { ex ->
                    log.warn(ex) { "NVD response was not valid JSON for topic=${topic.topicKey}" }
                    return emptyList()
                }
        val vulnerabilities = root["vulnerabilities"] ?: return emptyList()
        return vulnerabilities.mapNotNull { toRawEvent(node = it) }
    }

    private fun toRawEvent(node: JsonNode): RawSourceEvent? {
        val cve = node["cve"] ?: return null
        val externalId = cve["id"]?.stringOrNull() ?: return null
        val description = englishDescription(cve = cve)
        val firstLine =
            description
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
        val title = if (firstLine.isBlank()) externalId else "$externalId $firstLine"
        val metrics = metricsLine(cve = cve)
        val rawContent = if (metrics.isBlank()) description else "$description\n\n$metrics"
        return RawSourceEvent(
            externalId = externalId,
            title = title,
            rawContent = rawContent,
            publishedAt = parseSourceTimestamp(value = cve["published"]?.stringOrNull()),
        )
    }

    private fun englishDescription(cve: JsonNode): String =
        cve["descriptions"]
            ?.firstOrNull { it["lang"]?.stringOrNull() == "en" }
            ?.get("value")
            ?.stringOrNull()
            .orEmpty()

    private fun metricsLine(cve: JsonNode): String {
        val metric =
            cve["metrics"]?.let { metrics ->
                metrics["cvssMetricV31"]?.firstOrNull()
                    ?: metrics["cvssMetricV30"]?.firstOrNull()
                    ?: metrics["cvssMetricV2"]?.firstOrNull()
            } ?: return ""
        val cvssData = metric["cvssData"]
        val baseScore = cvssData?.get("baseScore")?.stringOrNull()
        // CVSS v2 carries severity on the metric node; v3 carries it inside cvssData.
        val baseSeverity = cvssData?.get("baseSeverity")?.stringOrNull() ?: metric["baseSeverity"]?.stringOrNull()
        if (baseScore == null && baseSeverity == null) return ""
        return buildString {
            append("CVSS")
            baseScore?.let { append(" baseScore=$it") }
            baseSeverity?.let { append(" baseSeverity=$it") }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val DEFAULT_API_BASE_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0"

        // NVD expects ISO-8601 extended with milliseconds; a bare seconds form is rejected.
        private val NVD_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
