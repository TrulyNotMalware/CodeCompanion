package dev.notypie.impl.cve

import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.schema.CveSourceType
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.OffsetDateTime

data class RawSourceEvent(
    val externalId: String,
    val title: String,
    val rawContent: String,
    val publishedAt: LocalDateTime?,
)

interface SourceAdapter {
    fun supports(sourceType: CveSourceType): Boolean

    fun fetch(topic: CveTopic): List<RawSourceEvent>
}

// Blanks fold to null (not just JSON null) — GitHub sends "name":"" for tag-only releases; ?: needs this.
internal fun JsonNode.stringOrNull(): String? =
    if (isValueNode && !isNull && !isMissingNode) asString().takeIf { it.isNotBlank() } else null

internal fun parseSourceTimestamp(value: String?): LocalDateTime? {
    if (value == null) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(value) }
        .getOrNull()
}
