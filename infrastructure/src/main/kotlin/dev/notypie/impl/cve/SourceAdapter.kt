package dev.notypie.impl.cve

import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.schema.CveSourceType
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * One item fetched from a source feed, already normalized to the fields the collector persists.
 * [externalId] is the source's stable id for the item and, with the topic, forms the ingestion
 * dedup key. [publishedAt] is best-effort — null when the source omits or malforms it.
 */
data class RawSourceEvent(
    val externalId: String,
    val title: String,
    val rawContent: String,
    val publishedAt: LocalDateTime?,
)

/**
 * Pluggable feed source. The collector resolves the adapter whose [supports] matches a topic's
 * `sourceType`, then calls [fetch]. Implementations live in infrastructure (HTTP is infra) and
 * are wired as beans behind the CVE feature gate. A [fetch] must never throw: a malformed
 * `source_config` or a non-2xx/transport failure returns an empty list so one bad topic can't
 * abort the collection tick (the collector also isolates per topic as a second line of defense).
 */
interface SourceAdapter {
    fun supports(sourceType: CveSourceType): Boolean

    fun fetch(topic: CveTopic): List<RawSourceEvent>
}

/**
 * Non-blank text of a scalar JSON field, or null when the field is absent, JSON null, non-scalar,
 * or blank. Blank folds to null so `?:` fallback chains work — GitHub sends `"name": ""` (not
 * null) for tag-only releases, and without this the tag_name fallback never fires.
 */
internal fun JsonNode.stringOrNull(): String? =
    if (isValueNode && !isNull && !isMissingNode) asString().takeIf { it.isNotBlank() } else null

/**
 * Parses a source timestamp to a local date-time, tolerating both offset-carrying forms (GitHub's
 * `2026-01-01T00:00:00Z`) and offset-free forms (NVD's `2026-01-01T00:00:00.000`). Returns null on
 * anything unparseable — publishedAt is best-effort metadata, never a reason to drop an event.
 */
internal fun parseSourceTimestamp(value: String?): LocalDateTime? {
    if (value == null) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(value) }
        .getOrNull()
}
