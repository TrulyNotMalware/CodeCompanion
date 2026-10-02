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
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

class NvdCveSourceAdapter(
    private val apiKey: String,
    private val lookbackMinutes: Long,
    private val requestTimeout: Duration,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val clock: Clock,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val requestInterval: Duration = DEFAULT_REQUEST_INTERVAL,
    private val onPageCapReached: (CveTopic) -> Unit = {},
) : SourceAdapter {
    private val pacing = ReentrantLock()
    private var nextRequestAtNanos: Long = System.nanoTime()

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    override fun supports(sourceType: CveSourceType): Boolean = sourceType == CveSourceType.NVD_CVE

    override fun fetch(topic: CveTopic): List<RawSourceEvent> {
        val matchParam = parseMatchParam(topic = topic) ?: return emptyList()
        // NVD treats offset-free timestamps as UTC; a zoned wall clock would shift the window and silently empty it.
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val query =
            listOf(
                matchParam,
                "resultsPerPage=$RESULTS_PER_PAGE",
                "lastModStartDate=${encode(value = now.minusMinutes(lookbackMinutes).format(NVD_DATE_FORMAT))}",
                "lastModEndDate=${encode(value = now.format(NVD_DATE_FORMAT))}",
            ).joinToString(separator = "&")

        val events = mutableListOf<RawSourceEvent>()
        var startIndex = 0
        repeat(MAX_PAGES) {
            val root = fetchPage(query = "$query&startIndex=$startIndex", topic = topic) ?: return events
            val vulnerabilities = root["vulnerabilities"]
            vulnerabilities?.mapNotNullTo(events) { toRawEvent(node = it) }
            val pageSize = vulnerabilities?.size() ?: 0
            startIndex += pageSize
            val totalResults = root["totalResults"]?.stringOrNull()?.toIntOrNull()
            if (pageSize == 0 || totalResults == null || startIndex >= totalResults) return events
        }
        log.warn {
            "NVD results for topic=${topic.topicKey} exceed $MAX_PAGES pages; stopped at startIndex=$startIndex"
        }
        onPageCapReached(topic)
        return events
    }

    private fun fetchPage(query: String, topic: CveTopic): JsonNode? {
        val request =
            HttpRequest
                .newBuilder(URI.create("$apiBaseUrl?$query"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .apply { if (apiKey.isNotBlank()) header("apiKey", apiKey) }
                .GET()
                .build()

        awaitRequestSlot()
        val response =
            try {
                httpClient.sendWithinDeadline(request = request, deadline = requestTimeout, maxBodyBytes = maxBodyBytes)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            } catch (exception: Exception) {
                log.warn(exception) { "NVD request failed for topic=${topic.topicKey}" }
                return null
            }
        if (response.statusCode in REFUSED_STATUSES) {
            log.warn {
                "NVD refused topic=${topic.topicKey} with ${response.statusCode} (rate limit or outage); " +
                    "a later window's $lookbackMinutes-minute lookback re-reads this period"
            }
            return null
        }
        if (response.statusCode !in 200..299) {
            log.warn { "NVD returned ${response.statusCode} for topic=${topic.topicKey}" }
            return null
        }
        return try {
            jsonMapper.readTree(response.body)
        } catch (exception: Exception) {
            log.warn(exception) { "NVD response was not valid JSON for topic=${topic.topicKey}" }
            null
        }
    }

    // Topics are fetched in a fixed order each tick, so without spacing the same topics past the quota fail every time.
    private fun awaitRequestSlot() {
        pacing.withLock {
            val waitNanos = nextRequestAtNanos - System.nanoTime()
            if (waitNanos > 0L) {
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw exception
                }
            }
            nextRequestAtNanos = System.nanoTime() + requestInterval.toNanos()
        }
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
        const val DEFAULT_MAX_BODY_BYTES = 32 * 1024 * 1024

        // Half of NVD's 2,000 maximum: each page is read whole, and a page past maxBodyBytes is dropped.
        const val RESULTS_PER_PAGE = 1_000
        const val MAX_PAGES = 10

        // NVD: 5 requests per rolling 30 s without an API key (50 with one); its guidance is 6 s between requests.
        val DEFAULT_REQUEST_INTERVAL: Duration = Duration.ofSeconds(6L)
        private val REFUSED_STATUSES = setOf(403, 429, 503)

        // NVD expects ISO-8601 extended with milliseconds; a bare seconds form is rejected.
        private val NVD_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
