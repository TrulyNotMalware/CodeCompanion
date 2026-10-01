package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import java.time.LocalDateTime

data class UndeliveredCveEvent(
    val eventId: Long,
    val userId: String,
    val topicKey: String,
    val topicDisplayName: String,
    val title: String,
    val aiSummary: String?,
)

interface CveDeliveryRepository {
    fun claim(eventId: Long, userId: String): Boolean

    fun findUndelivered(
        deliveryMode: CveDeliveryMode,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent>

    // Same filter as findUndelivered, ordered by user then event so one user's pairs are never interleaved.
    fun findUndeliveredByUser(
        deliveryMode: CveDeliveryMode,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent>

    // Same filter as findUndelivered for one user only, in event order: the rest of a digest day that a single
    // subscriber's pairs pushed past a findUndeliveredByUser page.
    fun findUndeliveredForUser(
        deliveryMode: CveDeliveryMode,
        userId: String,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent>

    fun dbNow(): LocalDateTime
}
